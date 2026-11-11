package mari.samba.infra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mari.samba.share.SambaShare;
import mari.samba.share.SambaShareCreateDto;
import mari.samba.smbconfig.SambaGlobalConfigDto;
import org.springframework.stereotype.Component;

@Component
public class SmbConfParser {

    /**
     * Parses the effective Samba configuration dump (e.g. from {@code testparm -sv})
     * into a map of section names to key-value maps. Keys are normalized to lowercase
     * with collapsed whitespaces.
     *
     * @param content the raw output of testparm -sv
     * @return map of section names to key-value pairs
     */
    public Map<String, Map<String, String>> parseEffectiveConfig(String content) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return sections;
        }

        String[] lines = content.split("\\r?\\n");
        Map<String, String> currentMap = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue;
            }

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                String section = trimmed.substring(1, trimmed.length() - 1).trim();
                currentMap = sections.computeIfAbsent(section, k -> new LinkedHashMap<>());
                continue;
            }

            if (currentMap != null && trimmed.contains("=")) {
                String[] parts = trimmed.split("=", 2);
                String key = parts[0].trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                String val = parts[1].trim();
                currentMap.put(key, val);
            }
        }
        return sections;
    }

    public SambaGlobalConfigDto parseGlobalConfig(String content) {
        if (content == null || content.isBlank()) {
            return new SambaGlobalConfigDto();
        }

        SambaGlobalConfigDto.Builder builder = SambaGlobalConfigDto.builder();
        String[] lines = content.split("\\r?\\n");
        boolean insideGlobal = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue;
            }

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                String section = trimmed.substring(1, trimmed.length() - 1).trim();
                insideGlobal = section.equalsIgnoreCase("global");
                continue;
            }

            if (insideGlobal && trimmed.contains("=")) {
                String[] parts = trimmed.split("=", 2);
                String key = parts[0].trim().toLowerCase(Locale.ROOT);
                String val = parts[1].trim();

                switch (key) {
                    case "workgroup" -> builder.workgroup(val);
                    case "server string" -> builder.serverString(val);
                    case "netbios name" -> builder.netbiosName(val);
                    case "security" -> builder.security(val.toLowerCase(Locale.ROOT));
                    case "map to guest" -> builder.mapToGuest(val);
                    case "interfaces" -> builder.interfaces(val);
                    case "bind interfaces only" -> builder.bindInterfacesOnly(parseBoolean(val));
                    case "load printers" -> builder.loadPrinters(parseBoolean(val));
                    case "disable netbios" -> builder.disableNetbios(parseBoolean(val));
                    case "server min protocol" -> builder.serverMinProtocol(val.toUpperCase());
                    case "server max protocol" -> builder.serverMaxProtocol(val.toUpperCase());
                    case "realm" -> builder.realm(val);
                    case "winbind use default domain" -> builder.winbindUseDefaultDomain(parseBoolean(val));
                    case "template shell" -> builder.templateShell(val);
                    case "idmap config * : backend" -> builder.idmapDefaultBackend(val);
                    case "idmap config * : range" -> builder.idmapDefaultRange(val);
                    default -> {
                        if (key.startsWith("idmap config") && key.endsWith(": backend")) {
                            builder.idmapDomainBackend(val);
                        } else if (key.startsWith("idmap config") && key.endsWith(": range")) {
                            builder.idmapDomainRange(val);
                        }
                    }
                }
            }
        }
        return builder.build();
    }

    public String buildGlobalSection(SambaGlobalConfigDto dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("[global]\n");
        sb.append("   workgroup = ").append(dto.workgroup().trim()).append("\n");

        if (dto.serverString() != null && !dto.serverString().isBlank()) {
            sb.append("   server string = ").append(dto.serverString().trim()).append("\n");
        }
        if (dto.netbiosName() != null && !dto.netbiosName().isBlank()) {
            sb.append("   netbios name = ").append(dto.netbiosName().trim()).append("\n");
        }

        sb.append("   security = ")
                .append(dto.security() != null ? dto.security() : "user")
                .append("\n");
        sb.append("   map to guest = ")
                .append(dto.mapToGuest() != null ? dto.mapToGuest() : "Bad User")
                .append("\n");

        if (dto.interfaces() != null && !dto.interfaces().isBlank()) {
            sb.append("   interfaces = ").append(dto.interfaces().trim()).append("\n");
            sb.append("   bind interfaces only = ")
                    .append(dto.bindInterfacesOnly() ? "yes" : "no")
                    .append("\n");
        }

        sb.append("   load printers = ")
                .append(dto.loadPrinters() ? "yes" : "no")
                .append("\n");
        sb.append("   disable netbios = ")
                .append(dto.disableNetbios() ? "yes" : "no")
                .append("\n");

        if (dto.serverMinProtocol() != null && !dto.serverMinProtocol().isBlank()) {
            sb.append("   server min protocol = ")
                    .append(dto.serverMinProtocol().trim())
                    .append("\n");
        }
        if (dto.serverMaxProtocol() != null && !dto.serverMaxProtocol().isBlank()) {
            sb.append("   server max protocol = ")
                    .append(dto.serverMaxProtocol().trim())
                    .append("\n");
        }

        if (dto.realm() != null && !dto.realm().isBlank()) {
            sb.append("   realm = ").append(dto.realm().trim()).append("\n");
        }
        sb.append("   winbind use default domain = ")
                .append(dto.winbindUseDefaultDomain() ? "yes" : "no")
                .append("\n");

        if (dto.templateShell() != null && !dto.templateShell().isBlank()) {
            sb.append("   template shell = ").append(dto.templateShell().trim()).append("\n");
        }

        if (dto.idmapDefaultBackend() != null && !dto.idmapDefaultBackend().isBlank()) {
            sb.append("   idmap config * : backend = ")
                    .append(dto.idmapDefaultBackend().trim())
                    .append("\n");
        }
        if (dto.idmapDefaultRange() != null && !dto.idmapDefaultRange().isBlank()) {
            sb.append("   idmap config * : range = ")
                    .append(dto.idmapDefaultRange().trim())
                    .append("\n");
        }

        if ("ads".equalsIgnoreCase(dto.security()) && dto.workgroup() != null) {
            if (dto.idmapDomainBackend() != null && !dto.idmapDomainBackend().isBlank()) {
                sb.append("   idmap config ")
                        .append(dto.workgroup().trim())
                        .append(" : backend = ")
                        .append(dto.idmapDomainBackend().trim())
                        .append("\n");
                sb.append("   idmap config ")
                        .append(dto.workgroup().trim())
                        .append(" : range = ")
                        .append(dto.idmapDomainRange().trim())
                        .append("\n");
            }
        }

        return sb.toString();
    }

    public String updateGlobalSection(String content, SambaGlobalConfigDto dto) {
        String updatedWithoutGlobal;
        try {
            updatedWithoutGlobal = removeSection(content, "global");
        } catch (Exception e) {
            updatedWithoutGlobal = content;
        }

        String newGlobalSection = buildGlobalSection(dto);
        return newGlobalSection + "\n" + updatedWithoutGlobal.trim() + "\n";
    }

    public List<SambaShare> parseShares(String content) {
        List<SambaShare> shares = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return shares;
        }

        String[] lines = content.split("\\r?\\n");
        SambaShare.Builder currentShare = null;
        boolean insideShare = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue;
            }

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                String sectionName = trimmed.substring(1, trimmed.length() - 1).trim();
                if (!sectionName.equalsIgnoreCase("global")
                        && !sectionName.equalsIgnoreCase("homes")
                        && !sectionName.equalsIgnoreCase("printers")) {

                    if (currentShare != null) {
                        shares.add(currentShare.build());
                    }
                    currentShare = SambaShare.builder().name(sectionName);
                    insideShare = true;
                } else {
                    if (currentShare != null) {
                        shares.add(currentShare.build());
                    }
                    insideShare = false;
                    currentShare = null;
                }
                continue;
            }

            if (insideShare && currentShare != null && trimmed.contains("=")) {
                String[] parts = trimmed.split("=", 2);
                String key = parts[0].trim().toLowerCase(Locale.ROOT);
                String value = parts[1].trim();

                mapProperty(currentShare, key, value);
            }
        }

        if (currentShare != null) {
            shares.add(currentShare.build());
        }

        return shares;
    }

    public String buildShareSection(SambaShareCreateDto dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(dto.getName()).append("]\n");
        sb.append("   path = ").append(dto.getPath()).append("\n");

        appendIfPresent(sb, "comment", dto.getComment());
        sb.append("   read only = ").append(dto.isReadOnly() ? "yes" : "no").append("\n");
        sb.append("   guest ok = ").append(dto.isGuestOk() ? "yes" : "no").append("\n");
        sb.append("   browseable = ").append(dto.isBrowseable() ? "yes" : "no").append("\n");

        appendIfPresent(sb, "valid users", dto.getValidUsers());
        appendIfPresent(sb, "write list", dto.getWriteList());
        appendIfPresent(sb, "create mask", dto.getCreateMask());
        appendIfPresent(sb, "directory mask", dto.getDirectoryMask());
        appendIfPresent(sb, "force user", dto.getForceUser());
        appendIfPresent(sb, "force group", dto.getForceGroup());
        appendIfPresent(sb, "max connections", dto.getMaxConnections());
        appendIfPresent(sb, "hosts allow", dto.getHostsAllow());
        appendIfPresent(sb, "hosts deny", dto.getHostsDeny());

        return sb.toString();
    }

    public String removeSection(String content, String sectionNameToRemove) {
        String[] lines = content.split("\\r?\\n");
        StringBuilder result = new StringBuilder();
        boolean insideTargetSection = false;
        boolean sectionFound = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                String currentSection =
                        trimmed.substring(1, trimmed.length() - 1).trim();
                if (currentSection.equalsIgnoreCase(sectionNameToRemove)) {
                    insideTargetSection = true;
                    sectionFound = true;
                    continue;
                } else {
                    insideTargetSection = false;
                }
            }

            if (!insideTargetSection) {
                result.append(line).append("\n");
            }
        }

        if (!sectionFound) {
            throw new IllegalArgumentException("Section [" + sectionNameToRemove + "] not found in configuration");
        }

        return result.toString();
    }

    private boolean parseBoolean(String value) {
        return "yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private void mapProperty(SambaShare.Builder share, String key, String value) {
        switch (key) {
            case "path" -> share.path(value);
            case "comment" -> share.comment(value);
            case "read only" -> share.readOnly(parseBoolean(value));
            case "guest ok" -> share.guestOk(parseBoolean(value));
            case "browseable" -> share.browseable(parseBoolean(value));
            case "valid users" -> share.validUsers(value);
            case "write list" -> share.writeList(value);
            case "create mask" -> share.createMask(value);
            case "directory mask" -> share.directoryMask(value);
            case "force user" -> share.forceUser(value);
            case "force group" -> share.forceGroup(value);
            case "max connections" -> share.maxConnections(value);
            case "hosts allow" -> share.hostsAllow(value);
            case "hosts deny" -> share.hostsDeny(value);
            default -> {}
        }
    }

    private void appendIfPresent(StringBuilder sb, String propertyName, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("   ")
                    .append(propertyName)
                    .append(" = ")
                    .append(value.trim())
                    .append("\n");
        }
    }
}
