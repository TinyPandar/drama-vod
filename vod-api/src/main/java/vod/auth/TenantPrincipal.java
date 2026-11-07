package vod.auth;

public record TenantPrincipal(long tenantId, String scopes) {
    public boolean allows(String scope) {
        if (scopes == null || scopes.isBlank()) return false;
        for (String part : scopes.split(",")) {
            if (part.trim().equals(scope) || part.trim().equals("*")) return true;
        }
        return false;
    }
}
