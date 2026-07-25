package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.config.ConfigIssue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes overlay documents for the configuration API.
 *
 * <p>The ordering here is the whole design: <b>scan, then parse</b>, exactly as
 * {@link UserConfigLoader} does for the file on disk. A credential must be
 * refused even when the rest of the document is malformed, and must never reach
 * a log line, an exception message, or a response body on its way to being
 * refused.
 *
 * <p>Binding is strict in both directions. An unrecognised field is an error
 * rather than a silent no-op, because a mistyped key that is quietly dropped
 * looks exactly like one that took effect — and here the user would then see it
 * vanish from a file they just saved.
 */
@Component
public class UserConfigCodec {

    private final ObjectMapper json;
    private final ObjectMapper yaml;
    private final ObjectMapper lenientJson;
    private final ObjectMapper lenientYaml;
    private final SecretScanner secretScanner;

    /**
     * @param secretScanner refuses credential material before anything is parsed
     */
    public UserConfigCodec(SecretScanner secretScanner) {
        this.secretScanner = secretScanner;
        this.json = strict(new ObjectMapper());
        this.yaml = strict(new ObjectMapper(new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)));
        // The lenient mappers exist only to walk field names before strict
        // binding rejects them. They never produce a document.
        this.lenientJson = new ObjectMapper();
        this.lenientYaml = new ObjectMapper(new YAMLFactory());
    }

    private ObjectMapper strict(ObjectMapper mapper) {
        return mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                     .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                     .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                     .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    }

    /**
     * Parse a JSON overlay document, as the configuration UI submits it.
     *
     * @param raw the request body
     * @return the parsed document; an empty document when the body is blank
     * @throws UserConfigDocumentException when the body carries credential
     *                                     material, cannot be parsed, or
     *                                     declares an unsupported version
     */
    public UserConfigDocument readJson(String raw) {
        return read(raw, json, lenientJson, "JSON");
    }

    /**
     * Parse a YAML overlay document, as an import or the file on disk carries it.
     *
     * @param raw the document text
     * @return the parsed document; an empty document when the text is blank
     * @throws UserConfigDocumentException when the text carries credential
     *                                     material, cannot be parsed, or
     *                                     declares an unsupported version
     */
    public UserConfigDocument readYaml(String raw) {
        return read(raw, yaml, lenientYaml, "YAML");
    }

    /**
     * Render a document as the YAML that would be written to disk.
     *
     * <p>The output is scanned too. Serialising is the last point at which a
     * credential smuggled through a free-text field — a {@code providerModelId}
     * holding an API key, say — can be caught before it is written somewhere
     * durable.
     *
     * @param document the document to render
     * @return YAML text with a header explaining what the file is
     * @throws UserConfigDocumentException when the rendered document carries
     *                                     credential material
     */
    public String writeYaml(UserConfigDocument document) {
        return render(document, HEADER);
    }

    /**
     * Render a saved proposal as YAML.
     *
     * <p>A sibling of {@link #writeYaml(UserConfigDocument)} rather than a
     * generic {@code writeYaml(Object)}, so no future caller can route an
     * arbitrary object past the credential scan by widening a parameter. The
     * scan is the same one, for the same reason: serialising is the last point
     * at which something smuggled through a free-text field can be caught before
     * it is written somewhere durable.
     *
     * @param proposal the proposal to render
     * @return YAML text, headed with an explanation that it is not live config
     * @throws UserConfigDocumentException when the rendered document carries
     *                                     credential material
     */
    public String writeProposalYaml(Object proposal) {
        return render(proposal, PROPOSAL_HEADER);
    }

    /**
     * Parse a saved proposal.
     *
     * @param raw  the file's contents
     * @param type the proposal type to bind to
     * @param <T>  the proposal type
     * @return the parsed proposal
     * @throws UserConfigDocumentException when the text carries credential
     *                                     material or cannot be parsed
     */
    public <T> T readProposalYaml(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            throw new UserConfigDocumentException(List.of(new ConfigIssue(
                    ConfigIssue.Severity.ERROR, "file", null, "The proposal file is empty.", null)));
        }
        List<ConfigIssue> issues = scan(raw, lenientYaml);
        if (!issues.isEmpty()) {
            throw new UserConfigDocumentException(issues);
        }
        try {
            return yaml.readValue(raw, type);
        } catch (Exception ex) {
            throw new UserConfigDocumentException(List.of(new ConfigIssue(
                    ConfigIssue.Severity.ERROR, "file", null,
                    "The proposal could not be parsed: " + rootMessage(ex),
                    "Discard it and run the advisor again.")));
        }
    }

    private String render(Object value, String header) {
        String body;
        try {
            body = yaml.writeValueAsString(value);
        } catch (Exception ex) {
            throw new UserConfigDocumentException(List.of(new ConfigIssue(
                    ConfigIssue.Severity.ERROR, "file", null,
                    "The configuration could not be rendered: " + rootMessage(ex), null)));
        }
        String rendered = header + body;
        List<ConfigIssue> issues = scan(rendered, lenientYaml);
        if (!issues.isEmpty()) {
            throw new UserConfigDocumentException(issues);
        }
        return rendered;
    }

    private static final String PROPOSAL_HEADER = """
            # LLM Council saved proposal — NOT active configuration.
            #
            # A council the requirement advisor produced and nobody applied yet.
            # Nothing here is in effect. This file is never read at startup, and
            # the 'kind' field below means that copying it over council-user.yml
            # does not quietly turn it into configuration: it is refused, and the
            # reason is reported.
            #
            # Apply or discard it from the setup wizard.
            """;

    private static final String HEADER = """
            # LLM Council user configuration.
            #
            # Written by the configuration API. Hand-editing is supported; the file
            # is validated at startup and an invalid entity is dropped and reported
            # rather than preventing the application from starting.
            #
            # This file never contains credentials. Providers are activated by
            # environment variables; GET /api/council/catalog?include=providers
            # reports which are set and names the variable for those that are not.
            """;

    private UserConfigDocument read(String raw, ObjectMapper strict, ObjectMapper lenient, String format) {
        if (raw == null || raw.isBlank()) {
            return UserConfigDocument.empty();
        }

        List<ConfigIssue> issues = scan(raw, lenient);
        if (!issues.isEmpty()) {
            throw new UserConfigDocumentException(issues);
        }

        UserConfigDocument document;
        try {
            document = strict.readValue(raw, UserConfigDocument.class);
        } catch (Exception ex) {
            throw new UserConfigDocumentException(List.of(new ConfigIssue(
                    ConfigIssue.Severity.ERROR, "file", null,
                    "The " + format + " document could not be parsed: " + rootMessage(ex),
                    "Fix the reported field or remove it. Nothing has been written.")));
        }
        if (document == null) {
            return UserConfigDocument.empty();
        }
        if (document.version() != null && document.version() != UserConfigDocument.SUPPORTED_VERSION) {
            throw new UserConfigDocumentException(List.of(new ConfigIssue(
                    ConfigIssue.Severity.ERROR, "file", "version",
                    "The document declares version " + document.version()
                    + " but this application understands version "
                    + UserConfigDocument.SUPPORTED_VERSION + ".",
                    "Set version: " + UserConfigDocument.SUPPORTED_VERSION + ".")));
        }
        return document;
    }

    /**
     * Look for credential material by field name and by value shape.
     *
     * <p>Two passes because they fail differently. The text pass catches a key
     * written at the start of a line, which is what a hand-edited YAML file looks
     * like; the tree walk catches one nested anywhere in a request body, which a
     * line-anchored pattern cannot see. Neither ever reports the value.
     */
    private List<ConfigIssue> scan(String raw, ObjectMapper lenient) {
        List<ConfigIssue> issues = new ArrayList<>(secretScanner.scan(raw));
        Set<String> alreadyReported = new LinkedHashSet<>();
        issues.forEach(issue -> {
            if (issue.field() != null) {
                alreadyReported.add(issue.field().toLowerCase(java.util.Locale.ROOT));
            }
        });

        JsonNode root;
        try {
            root = lenient.readTree(raw);
        } catch (Exception ex) {
            // Unparseable: the text pass is all the protection available, and the
            // parse failure is reported by the caller.
            return issues;
        }
        walk(root, "", issues, alreadyReported);
        return issues;
    }

    private void walk(JsonNode node, String path, List<ConfigIssue> issues, Set<String> alreadyReported) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String name = entry.getKey();
                String child = path.isEmpty() ? name : path + "." + name;
                if (secretScanner.isCredentialFieldName(name)
                    && alreadyReported.add(name.toLowerCase(java.util.Locale.ROOT))) {
                    issues.add(new ConfigIssue(
                            ConfigIssue.Severity.ERROR, "file", child,
                            "The document contains a credential field '" + name + "' at " + child
                            + ". This application never reads credentials from configuration. "
                            + "The value has not been logged and nothing has been written.",
                            "Remove the field and set the provider's environment variable instead. "
                            + "Provider status and the variable to set are shown by "
                            + "GET /api/council/catalog?include=providers."));
                }
                walk(entry.getValue(), child, issues, alreadyReported);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", issues, alreadyReported);
            }
        }
    }

    /**
     * Extract the most specific message from a parse failure.
     *
     * <p>Jackson nests the useful detail — which field, which line — inside
     * wrapper exceptions whose own messages are generic.
     */
    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message.split("\n")[0];
    }
}
