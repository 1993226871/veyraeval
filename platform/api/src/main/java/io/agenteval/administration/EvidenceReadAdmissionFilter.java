package io.agenteval.administration;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Holds admission through synchronous JSON serialization, including a slow or disconnected response consumer. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class EvidenceReadAdmissionFilter extends OncePerRequestFilter {
    private static final Pattern LARGE_READ=Pattern.compile("/api/v1/(?:evidence/snapshots/[^/]+|reports/(?:experiments/[^/]+(?:/(?:export|trials))?|comparison(?:/trials)?)|catalog|experiments)");
    private final Semaphore slots=new Semaphore(2);
    private final ConcurrentHashMap<String,Boolean> users=new ConcurrentHashMap<>();
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getMethod().equals("GET") || !LARGE_READ.matcher(request.getRequestURI()).matches(); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(!slots.tryAcquire()) { reject(response); return; }
        String user=request.getUserPrincipal()==null?"anonymous":Objects.toString(request.getUserPrincipal().getName(),"anonymous");
        // A page needs its cheap scalar summary concurrently. Both still share the two process slots.
        boolean scalar=request.getRequestURI().matches("/api/v1/reports/(?:experiments/[^/]+|comparison)");
        user+=scalar?"|summary":"|body";
        if(users.putIfAbsent(user,Boolean.TRUE)!=null) { slots.release(); reject(response); return; }
        try { chain.doFilter(request,response); }
        finally { users.remove(user); slots.release(); }
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429); response.setContentType("application/problem+json"); response.setHeader("Cache-Control","no-store");
        response.getWriter().write("{\"status\":429,\"code\":\"CAPACITY_REACHED\",\"detail\":\"Large evidence reads are at capacity\",\"request_id\":\""+UUID.randomUUID()+"\"}");
    }
}
