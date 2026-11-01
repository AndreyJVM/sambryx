package mari.samba.smbconfig;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Data Transfer Object representing the {@code [global]} configuration section in {@code smb.conf}.
 */
public record SambaGlobalConfigDto(
        @NotBlank(message = "Workgroup name cannot be blank")
                @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "Workgroup name contains invalid characters")
                @JsonProperty("workgroup")
                String workgroup,
        @JsonProperty("serverString") String serverString,
        @JsonProperty("netbiosName") String netbiosName,
        @JsonProperty("security") String security,
        @JsonProperty("mapToGuest") String mapToGuest,
        @JsonProperty("interfaces") String interfaces,
        @JsonProperty("bindInterfacesOnly") boolean bindInterfacesOnly,
        @JsonProperty("loadPrinters") boolean loadPrinters,
        @JsonProperty("disableNetbios") boolean disableNetbios,
        @JsonProperty("serverMinProtocol") String serverMinProtocol,
        @JsonProperty("serverMaxProtocol") String serverMaxProtocol,
        @JsonProperty("realm") String realm,
        @JsonProperty("winbindUseDefaultDomain") boolean winbindUseDefaultDomain,
        @JsonProperty("idmapDefaultBackend") String idmapDefaultBackend,
        @JsonProperty("idmapDefaultRange") String idmapDefaultRange,
        @JsonProperty("idmapDomainBackend") String idmapDomainBackend,
        @JsonProperty("idmapDomainRange") String idmapDomainRange,
        @JsonProperty("templateShell") String templateShell) {

    /** Compact constructor that applies default values to all essential missing properties. */
    public SambaGlobalConfigDto {
        if (workgroup == null || workgroup.isBlank()) {
            workgroup = "WORKGROUP";
        }
        if (serverString == null) {
            serverString = "Samba Server";
        }
        if (security == null) {
            security = "user";
        }
        if (mapToGuest == null) {
            mapToGuest = "Bad User";
        }
        if (serverMinProtocol == null) {
            serverMinProtocol = "SMB2";
        }
        if (serverMaxProtocol == null) {
            serverMaxProtocol = "SMB3";
        }
        if (idmapDefaultBackend == null) {
            idmapDefaultBackend = "tdb";
        }
        if (idmapDefaultRange == null) {
            idmapDefaultRange = "3000-7999";
        }
        if (idmapDomainBackend == null) {
            idmapDomainBackend = "rid";
        }
        if (idmapDomainRange == null) {
            idmapDomainRange = "10000-999999";
        }
        if (templateShell == null) {
            templateShell = "/bin/bash";
        }
    }

    /** Default constructor that initializes all properties to their standard fallback values. */
    public SambaGlobalConfigDto() {
        this(
                "WORKGROUP",
                "Samba Server",
                null,
                "user",
                "Bad User",
                null,
                false,
                false,
                false,
                "SMB2",
                "SMB3",
                null,
                true,
                "tdb",
                "3000-7999",
                "rid",
                "10000-999999",
                "/bin/bash");
    }

    /**
     * Legacy constructor provided for backward compatibility with systems omitting domain and idmap
     * settings.
     */
    public SambaGlobalConfigDto(
            String workgroup,
            String serverString,
            String netbiosName,
            String security,
            String mapToGuest,
            String interfaces,
            boolean bindInterfacesOnly,
            boolean loadPrinters,
            boolean disableNetbios,
            String serverMinProtocol,
            String serverMaxProtocol) {
        this(
                workgroup,
                serverString,
                netbiosName,
                security,
                mapToGuest,
                interfaces,
                bindInterfacesOnly,
                loadPrinters,
                disableNetbios,
                serverMinProtocol,
                serverMaxProtocol,
                null,
                true,
                "tdb",
                "3000-7999",
                "rid",
                "10000-999999",
                "/bin/bash");
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters for backwards compatibility
    public String getWorkgroup() {
        return workgroup;
    }

    public String getServerString() {
        return serverString;
    }

    public String getNetbiosName() {
        return netbiosName;
    }

    public String getSecurity() {
        return security;
    }

    public String getMapToGuest() {
        return mapToGuest;
    }

    public String getInterfaces() {
        return interfaces;
    }

    public boolean isBindInterfacesOnly() {
        return bindInterfacesOnly;
    }

    public boolean isLoadPrinters() {
        return loadPrinters;
    }

    public boolean disableNetbios() {
        return disableNetbios;
    }

    public boolean isDisableNetbios() {
        return disableNetbios;
    }

    public String getServerMinProtocol() {
        return serverMinProtocol;
    }

    public String getServerMaxProtocol() {
        return serverMaxProtocol;
    }

    public String getRealm() {
        return realm;
    }

    public boolean isWinbindUseDefaultDomain() {
        return winbindUseDefaultDomain;
    }

    public String getIdmapDefaultBackend() {
        return idmapDefaultBackend;
    }

    public String getIdmapDefaultRange() {
        return idmapDefaultRange;
    }

    public String getIdmapDomainBackend() {
        return idmapDomainBackend;
    }

    public String getIdmapDomainRange() {
        return idmapDomainRange;
    }

    public String getTemplateShell() {
        return templateShell;
    }

    public static class Builder {
        private String workgroup = "WORKGROUP";
        private String serverString = "Samba Server";
        private String netbiosName;
        private String security = "user";
        private String mapToGuest = "Bad User";
        private String interfaces;
        private boolean bindInterfacesOnly = false;
        private boolean loadPrinters = false;
        private boolean disableNetbios = false;
        private String serverMinProtocol = "SMB2";
        private String serverMaxProtocol = "SMB3";
        private String realm;
        private boolean winbindUseDefaultDomain = true;
        private String idmapDefaultBackend = "tdb";
        private String idmapDefaultRange = "3000-7999";
        private String idmapDomainBackend = "rid";
        private String idmapDomainRange = "10000-999999";
        private String templateShell = "/bin/bash";

        public Builder workgroup(String workgroup) {
            this.workgroup = workgroup;
            return this;
        }

        public Builder serverString(String serverString) {
            this.serverString = serverString;
            return this;
        }

        public Builder netbiosName(String netbiosName) {
            this.netbiosName = netbiosName;
            return this;
        }

        public Builder security(String security) {
            this.security = security;
            return this;
        }

        public Builder mapToGuest(String mapToGuest) {
            this.mapToGuest = mapToGuest;
            return this;
        }

        public Builder interfaces(String interfaces) {
            this.interfaces = interfaces;
            return this;
        }

        public Builder bindInterfacesOnly(boolean bindInterfacesOnly) {
            this.bindInterfacesOnly = bindInterfacesOnly;
            return this;
        }

        public Builder loadPrinters(boolean loadPrinters) {
            this.loadPrinters = loadPrinters;
            return this;
        }

        public Builder disableNetbios(boolean disableNetbios) {
            this.disableNetbios = disableNetbios;
            return this;
        }

        public Builder serverMinProtocol(String serverMinProtocol) {
            this.serverMinProtocol = serverMinProtocol;
            return this;
        }

        public Builder serverMaxProtocol(String serverMaxProtocol) {
            this.serverMaxProtocol = serverMaxProtocol;
            return this;
        }

        public Builder realm(String realm) {
            this.realm = realm;
            return this;
        }

        public Builder winbindUseDefaultDomain(boolean winbindUseDefaultDomain) {
            this.winbindUseDefaultDomain = winbindUseDefaultDomain;
            return this;
        }

        public Builder idmapDefaultBackend(String idmapDefaultBackend) {
            this.idmapDefaultBackend = idmapDefaultBackend;
            return this;
        }

        public Builder idmapDefaultRange(String idmapDefaultRange) {
            this.idmapDefaultRange = idmapDefaultRange;
            return this;
        }

        public Builder idmapDomainBackend(String idmapDomainBackend) {
            this.idmapDomainBackend = idmapDomainBackend;
            return this;
        }

        public Builder idmapDomainRange(String idmapDomainRange) {
            this.idmapDomainRange = idmapDomainRange;
            return this;
        }

        public Builder templateShell(String templateShell) {
            this.templateShell = templateShell;
            return this;
        }

        public SambaGlobalConfigDto build() {
            return new SambaGlobalConfigDto(
                    workgroup,
                    serverString,
                    netbiosName,
                    security,
                    mapToGuest,
                    interfaces,
                    bindInterfacesOnly,
                    loadPrinters,
                    disableNetbios,
                    serverMinProtocol,
                    serverMaxProtocol,
                    realm,
                    winbindUseDefaultDomain,
                    idmapDefaultBackend,
                    idmapDefaultRange,
                    idmapDomainBackend,
                    idmapDomainRange,
                    templateShell);
        }
    }
}
