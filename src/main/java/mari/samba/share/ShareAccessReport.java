package mari.samba.share;

import java.util.List;

public record ShareAccessReport(String share, String username, List<Finding> findings) {
    public record Finding(String code, String status, String evidence, String nextAction) {}
}
