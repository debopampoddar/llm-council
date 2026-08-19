#!/usr/bin/env ruby
# frozen_string_literal: true

require "pathname"
require "uri"

root = Pathname.new(__dir__).join("..").cleanpath
files = Dir[root.join("{README.md,docs/**/*.md}")].sort
failures = []

files.each do |path|
  # Pin the encoding: File.read decodes using the locale's external encoding, so
  # under LC_CTYPE=C the repository's non-ASCII prose raises "invalid byte
  # sequence in US-ASCII" before a single link is checked.
  text = File.read(path, encoding: "UTF-8")
  text.scan(/!?\[[^\]]*\]\(([^)]+)\)/).flatten.each do |raw_target|
    target = raw_target.strip
    target = target[1...target.index(">")].to_s if target.start_with?("<")
    target = target.split(/\s+["']/, 2).first
    next if target.empty? || target.start_with?("#", "http://", "https://", "mailto:")

    decoded = URI::DEFAULT_PARSER.unescape(target.split("#", 2).first)
    resolved = Pathname.new(path).dirname.join(decoded).cleanpath
    next if resolved.exist?

    relative_file = Pathname.new(path).relative_path_from(root)
    failures << "#{relative_file}: missing target #{target}"
  end
end

unless failures.empty?
  warn "Markdown link validation failed:"
  failures.each { |failure| warn "  - #{failure}" }
  exit 1
end

puts "Markdown link validation passed (#{files.length} files)."
