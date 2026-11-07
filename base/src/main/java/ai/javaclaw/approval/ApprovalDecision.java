package ai.javaclaw.approval;

import java.util.Arrays;
import java.util.Optional;

/** An owner's answer to an approval request. {@link #wireValue()} is what clients send. */
public enum ApprovalDecision {

    /** Run this exact call, this one time. */
    ALLOW_ONCE("allow-once"),

    /** Do not run it; the model is told the user said no. */
    DENY("deny");

    private final String wireValue;

    ApprovalDecision(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ApprovalDecision> fromWireValue(String value) {
        return Arrays.stream(values()).filter(d -> d.wireValue.equals(value)).findFirst();
    }
}
