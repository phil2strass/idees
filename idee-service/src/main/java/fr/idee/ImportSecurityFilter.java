package fr.idee;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ImportSecurityFilter extends OncePerRequestFilter {
    private final String token;
    public ImportSecurityFilter(@Value("${idee.import-token}") String token) { this.token = token; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!java.util.Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                || request.getServletPath().startsWith("/api/admin/")) {
            response.setHeader("Cache-Control", "no-store");
            if (token.length() < 32) {
                error(response, 503, "Import non configure : une cle d'au moins 32 caracteres est requise.");
                return;
            }
            String header = request.getHeader("Authorization");
            String provided = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
            if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
                response.setHeader("WWW-Authenticate", "Bearer");
                error(response, 401, "Cle API absente ou invalide.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
    private void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
