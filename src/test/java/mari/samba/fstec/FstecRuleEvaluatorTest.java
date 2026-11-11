package mari.samba.fstec;

import static org.assertj.core.api.Assertions.assertThat;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Epic("Compliance")
@Feature("Unit: compliance rule operators")
class FstecRuleEvaluatorTest {

    private FstecRuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new FstecRuleEvaluator();
    }

    @ParameterizedTest
    @CsvSource({
        "0640, 0640, true",
        "0600, 0640, true",
        "0440, 0640, true",
        "0400, 0640, true",
        "0644, 0640, false",
        "0660, 0640, false",
        "0755, 0640, false",
        "0750, 0750, true",
        "0700, 0750, true",
        "0550, 0750, true",
        "0755, 0750, false",
        "0770, 0750, false",
        "0777, 0750, false"
    })
    @DisplayName("Should correctly evaluate EQUALS_OR_STRICTER_MASK")
    void testMaskComparison(String current, String expected, boolean shouldPass) {
        assertThat(evaluator.isEqualsOrStricterMask(current, expected)).isEqualTo(shouldPass);
        assertThat(evaluator.evaluate("EQUALS_OR_STRICTER_MASK", current, expected))
                .isEqualTo(shouldPass);
    }

    @Test
    @DisplayName("Should return false for invalid mask values")
    void testInvalidMask() {
        assertThat(evaluator.isEqualsOrStricterMask("invalid", "0640")).isFalse();
        assertThat(evaluator.isEqualsOrStricterMask(null, "0640")).isFalse();
        assertThat(evaluator.isEqualsOrStricterMask("0640", null)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "SMB2, SMB2, true",
        "SMB2_02, SMB2, true",
        "SMB2_10, SMB2, true",
        "SMB3, SMB2, true",
        "SMB3_11, SMB2, true",
        "NT1, SMB2, false",
        "LANMAN2, SMB2, false",
        "CORE, SMB2, false",
        "SMB3, SMB3, true",
        "SMB3_00, SMB3, true",
        "SMB3_11, SMB3, true",
        "SMB2, SMB3, false",
        "SMB2_02, SMB3, false",
        "NT1, SMB3, false"
    })
    @DisplayName("Should correctly evaluate AT_LEAST_PROTOCOL")
    void testProtocolComparison(String current, String expected, boolean shouldPass) {
        assertThat(evaluator.evaluateAtLeastProtocol(current, expected)).isEqualTo(shouldPass);
        assertThat(evaluator.evaluate("AT_LEAST_PROTOCOL", current, expected)).isEqualTo(shouldPass);
    }

    @Test
    @DisplayName("Should evaluate CONTAINS_ALL correctly")
    void testContainsAll() {
        String expected = "connect, open, mkdir, rmdir, unlink, write, rename, lock, pwrite, renameat, unlinkat";
        String actualWithAll = "connect open mkdir rmdir unlink write rename lock pwrite renameat unlinkat";
        String actualWithExtra =
                "connect, open, mkdir, rmdir, unlink, write, rename, lock, pwrite, renameat, unlinkat, close";
        String actualMissingOne = "connect open mkdir rmdir unlink write rename lock pwrite renameat";

        assertThat(evaluator.evaluateContainsAll(actualWithAll, expected)).isTrue();
        assertThat(evaluator.evaluateContainsAll(actualWithExtra, expected)).isTrue();
        assertThat(evaluator.evaluateContainsAll(actualMissingOne, expected)).isFalse();
        assertThat(evaluator.evaluateContainsAll(null, expected)).isFalse();
        assertThat(evaluator.evaluateContainsAll(actualWithAll, null)).isFalse();
    }

    @Test
    @DisplayName("Should evaluate EQUALS and NOT_EQUALS")
    void testEqualsAndNotEquals() {
        assertThat(evaluator.evaluateEquals("yes", "yes")).isTrue();
        assertThat(evaluator.evaluateEquals("Yes", "yes")).isTrue();
        assertThat(evaluator.evaluateEquals("no", "yes")).isFalse();
        assertThat(evaluator.evaluateEquals(null, "yes")).isFalse();

        assertThat(evaluator.evaluateNotEquals("Never", "Bad User")).isTrue();
        assertThat(evaluator.evaluateNotEquals("Bad User", "Bad User")).isFalse();
        assertThat(evaluator.evaluateNotEquals("192.168.1.0/24", "")).isTrue();
        assertThat(evaluator.evaluateNotEquals("", "")).isFalse();
        assertThat(evaluator.evaluateNotEquals(null, "")).isFalse();
    }

    @Test
    @DisplayName("Should evaluate ONE_OF and NOT_PRESENT_OR_NOT_EQUALS")
    void testOneOfAndNotPresent() {
        assertThat(evaluator.evaluateOneOf("SMB2", "SMB2, SMB3")).isTrue();
        assertThat(evaluator.evaluateOneOf("NT1", "SMB2, SMB3")).isFalse();

        assertThat(evaluator.evaluateNotPresentOrNotEquals(null, "Bad User")).isTrue();
        assertThat(evaluator.evaluateNotPresentOrNotEquals("", "Bad User")).isTrue();
        assertThat(evaluator.evaluateNotPresentOrNotEquals("Never", "Bad User")).isTrue();
        assertThat(evaluator.evaluateNotPresentOrNotEquals("Bad User", "Bad User"))
                .isFalse();
    }
}
