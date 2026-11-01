package mari.samba.fstec;

/** Compliance check result for a specific individual Samba network share. */
public record ShareResult(String shareName, FstecStatus status, String currentValue) {}
