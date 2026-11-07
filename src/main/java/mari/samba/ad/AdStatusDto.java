package mari.samba.ad;

public record AdStatusDto(
        Boolean isJoined,
        String statusMessage,
        String domainName,
        Boolean dnsReachable,
        Boolean kerberosWorking,
        Boolean winbindWorking) {}
