package mari.samba.filesystem;

import java.util.Locale;

/** Converts the first data row of df -Pk output into the API disk usage representation. */
final class DiskUsageParser {
    private DiskUsageParser() {}

    static DiskUsageDto parse(String safePath, String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new RuntimeException("Empty output from df command for path: " + safePath);
        }

        String[] lines = rawOutput.trim().split("\\r?\\n");
        if (lines.length < 2) {
            throw new RuntimeException("Invalid df output format: " + rawOutput);
        }

        String[] parts = lines[1].trim().split("\\s+");
        if (parts.length < 6) {
            throw new RuntimeException("Unexpected df line structure: " + lines[1]);
        }

        try {
            long totalKb = Long.parseLong(parts[1]);
            long usedKb = Long.parseLong(parts[2]);
            long availKb = Long.parseLong(parts[3]);
            int usePercent = Integer.parseInt(parts[4].replace("%", ""));
            String mountPoint = parts[5];

            return new DiskUsageDto(
                    safePath,
                    formatBytes(totalKb * 1024),
                    formatBytes(usedKb * 1024),
                    formatBytes(availKb * 1024),
                    usePercent,
                    mountPoint);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Failed to parse disk usage numbers from: " + lines[1], e);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
