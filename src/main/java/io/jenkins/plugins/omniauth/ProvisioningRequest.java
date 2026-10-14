package io.jenkins.plugins.omniauth;

import java.util.UUID;

/**
 * A self-service access request submitted through the provisioning portal.
 *
 * <p>No Jenkins account is created when a request is submitted — the request sits in the
 * {@link ProvisioningRequestConfig} store as {@code PENDING} until an administrator approves
 * or rejects it. On approval the admin side creates the account (mirroring {@code doAddUser}),
 * optionally time-limited by {@link #getRequestedDurationDays()}.
 *
 * <p>Modeled on {@link OmniAuthJitRequest}: non-null getters, a {@link #create} factory, string
 * status constants, and small display helpers so Jelly views stay simple.
 */
public class ProvisioningRequest {

    /** Requester authenticated through the identity provider (Entra SSO). */
    public static final String TYPE_ENTRA  = "ENTRA";
    /** Requester asking for a native (service) account. */
    public static final String TYPE_NATIVE = "NATIVE";

    public static final String STATUS_PENDING  = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private String requestId;
    private String requestType;   // ENTRA | NATIVE

    // Identity of the requested account
    private String userId;        // UPN/email (ENTRA) or username (NATIVE) — becomes the Jenkins user id
    private String fullName;
    private String email;
    private String oid;           // Entra object id, when known (ENTRA only)

    private String reason;
    private String requestedAccess; // free-text "what access do you need?" (optional, requester's words)
    private int    requestedDurationDays; // 0 = permanent

    private String status;
    private String requestedAt;   // ISO-8601 UTC
    private String sourceIp;      // best-effort, for audit + rate-limiting

    private String decidedAt;     // ISO-8601 UTC
    private String decidedBy;     // admin who approved/rejected
    private String decisionComment;

    public ProvisioningRequest() {}

    public static ProvisioningRequest create(String requestType, String userId, String fullName,
                                             String email, String oid, String reason,
                                             int requestedDurationDays, String sourceIp) {
        ProvisioningRequest r = new ProvisioningRequest();
        r.requestId             = UUID.randomUUID().toString();
        r.requestType           = requestType;
        r.userId                = userId != null ? userId.trim() : "";
        r.fullName              = fullName != null ? fullName.trim() : "";
        r.email                 = email != null ? email.trim() : "";
        r.oid                   = oid != null ? oid.trim() : "";
        r.reason                = reason != null ? reason.trim() : "";
        r.requestedDurationDays = Math.max(0, requestedDurationDays);
        r.status                = STATUS_PENDING;
        r.requestedAt           = java.time.Instant.now().toString();
        r.sourceIp              = sourceIp != null ? sourceIp : "";
        return r;
    }

    // ── Getters / setters ───────────────────────────────────────────────────

    public String getRequestId()              { return requestId != null ? requestId : ""; }
    public void   setRequestId(String v)      { this.requestId = v; }

    public String getRequestType()            { return requestType != null ? requestType : ""; }
    public void   setRequestType(String v)    { this.requestType = v; }

    public String getUserId()                 { return userId != null ? userId : ""; }
    public void   setUserId(String v)         { this.userId = v; }

    public String getFullName()               { return fullName != null ? fullName : ""; }
    public void   setFullName(String v)       { this.fullName = v; }

    public String getEmail()                  { return email != null ? email : ""; }
    public void   setEmail(String v)          { this.email = v; }

    public String getOid()                    { return oid != null ? oid : ""; }
    public void   setOid(String v)            { this.oid = v; }

    public String getReason()                 { return reason != null ? reason : ""; }
    public void   setReason(String v)         { this.reason = v; }

    public String getRequestedAccess()         { return requestedAccess != null ? requestedAccess : ""; }
    public void   setRequestedAccess(String v) { this.requestedAccess = v != null ? v.trim() : ""; }

    public int    getRequestedDurationDays()      { return requestedDurationDays; }
    public void   setRequestedDurationDays(int v) { this.requestedDurationDays = Math.max(0, v); }

    public String getStatus()                 { return status != null ? status : ""; }
    public void   setStatus(String v)         { this.status = v; }

    public String getRequestedAt()            { return requestedAt != null ? requestedAt : ""; }
    public void   setRequestedAt(String v)    { this.requestedAt = v; }

    public String getSourceIp()               { return sourceIp != null ? sourceIp : ""; }
    public void   setSourceIp(String v)       { this.sourceIp = v; }

    public String getDecidedAt()              { return decidedAt != null ? decidedAt : ""; }
    public void   setDecidedAt(String v)      { this.decidedAt = v; }

    public String getDecidedBy()              { return decidedBy != null ? decidedBy : ""; }
    public void   setDecidedBy(String v)      { this.decidedBy = v; }

    public String getDecisionComment()        { return decisionComment != null ? decisionComment : ""; }
    public void   setDecisionComment(String v){ this.decisionComment = v; }

    // ── State checks ────────────────────────────────────────────────────────

    public boolean isPending()  { return STATUS_PENDING.equals(status); }
    public boolean isApproved() { return STATUS_APPROVED.equals(status); }
    public boolean isRejected() { return STATUS_REJECTED.equals(status); }
    public boolean isCancelled(){ return STATUS_CANCELLED.equals(status); }

    public boolean isEntra()    { return TYPE_ENTRA.equals(requestType); }
    public boolean isNative()   { return TYPE_NATIVE.equals(requestType); }

    public boolean isPermanent() { return requestedDurationDays <= 0; }

    // ── Display helpers ───────────────────────────────────────────────────────

    public String typeLabel() {
        if (TYPE_ENTRA.equals(requestType))  return "Microsoft Entra";
        if (TYPE_NATIVE.equals(requestType)) return "Native";
        return getRequestType();
    }

    public String statusLabel() {
        if (status == null) return "";
        switch (status) {
            case STATUS_PENDING:   return "Pending";
            case STATUS_APPROVED:  return "Approved";
            case STATUS_REJECTED:  return "Rejected";
            case STATUS_CANCELLED: return "Cancelled";
            default:               return status;
        }
    }

    public String durationLabel() {
        return isPermanent() ? "Permanent" : requestedDurationDays + " day" + (requestedDurationDays == 1 ? "" : "s");
    }

    public String timeAgo() {
        if (requestedAt == null || requestedAt.isBlank()) return "";
        try {
            long mins = java.time.temporal.ChronoUnit.MINUTES.between(
                    java.time.Instant.parse(requestedAt), java.time.Instant.now());
            if (mins < 1)  return "just now";
            if (mins < 60) return mins + "m ago";
            long hrs = mins / 60;
            if (hrs < 24)  return hrs + "h ago";
            return (hrs / 24) + "d ago";
        } catch (Exception e) { return ""; }
    }
}
