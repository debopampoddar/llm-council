package com.debopam.llmcouncil.orchestration;

import java.util.List;

public record ValidationOutput(boolean approved,
                               double confidence,
                               List<String> issues,
                               List<String> recommendedFixes) {
}
