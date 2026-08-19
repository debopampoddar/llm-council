#!/usr/bin/env ruby
# frozen_string_literal: true

require "pathname"
require "date"
require "yaml"

root = Pathname.new(__dir__).join("..").cleanpath
files = Dir[root.join("**", "*.{yml,yaml}")].reject do |path|
  path.include?("/.git/") || path.include?("/target/")
end.sort

failures = []
files.each do |path|
  begin
    # Pin the encoding for the same reason verify-markdown-links.rb does: File.read
    # otherwise decodes using the locale's external encoding.
    YAML.safe_load(File.read(path, encoding: "UTF-8"), permitted_classes: [Date, Time], aliases: true, filename: path)
  rescue Psych::Exception => e
    failures << "#{Pathname.new(path).relative_path_from(root)}: #{e.message.lines.first.strip}"
  end
end

unless failures.empty?
  warn "YAML validation failed:"
  failures.each { |failure| warn "  - #{failure}" }
  exit 1
end

puts "YAML validation passed (#{files.length} files)."
