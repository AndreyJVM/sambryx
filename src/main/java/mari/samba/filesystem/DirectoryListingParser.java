package mari.samba.filesystem;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Converts the line-oriented find output into directory browser entries. */
final class DirectoryListingParser {
    private static final Logger log = LoggerFactory.getLogger(DirectoryListingParser.class);

    private DirectoryListingParser() {}

    static List<DirectoryItemDto> parse(String rawOutput) {
        return Arrays.stream(rawOutput.split("\\r?\\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(DirectoryListingParser::parseDirectoryLine)
                .filter(item -> item != null)
                .toList();
    }

    private static DirectoryItemDto parseDirectoryLine(String line) {
        String[] parts = line.split("\\|", 3);
        if (parts.length < 3) {
            return null;
        }
        String typeCode = parts[0];
        long size = 0;
        try {
            size = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            log.debug("Failed to parse size from directory listing: '{}'", parts[1]);
        }
        String fullPath = parts[2];
        String type = "d".equals(typeCode) ? "dir" : ("f".equals(typeCode) ? "file" : "link");
        String name = fullPath.substring(fullPath.lastIndexOf('/') + 1);
        return new DirectoryItemDto(name, fullPath, type, size);
    }
}
