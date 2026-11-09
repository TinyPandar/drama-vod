package vod.auth;

public record TenantPrincipal(long tenantId, String scopes) {
    public boolean allows(String scope) {
        if (scopes == null || scopes.isBlank()) return false;
        for (String part : scopes.split(",")) {
            if (part.trim().equals(scope) || part.trim().equals("*")) return true;
        }
        return false;
    }

    /** 接口入口调用：scope 不足时返回 403，不再往下查资源。 */
    public TenantPrincipal require(String scope) {
        if (!allows(scope)) throw new ScopeDeniedException();
        return this;
    }

    public static final class ScopeDeniedException extends RuntimeException {
        public ScopeDeniedException() {
            super("insufficient scope");
        }
    }
}
