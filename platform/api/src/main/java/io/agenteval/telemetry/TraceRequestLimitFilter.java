package io.agenteval.telemetry;
import io.agenteval.security.ApiProblems;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Bound both advertised and chunked request bodies before message conversion allocates them. */
@Component
public final class TraceRequestLimitFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getServletPath().endsWith("/events:batch") && !request.getRequestURI().endsWith("/events:batch") && !isSdkUpload(request); }
    private boolean isSdkUpload(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && java.util.Set.of("/api/v1/sdk-runs","/api/v1/sdk-runs:upload").contains(request.getServletPath());
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        int limit = isSdkUpload(req) ? 8*1024*1024 : 1024*1024;
        if(req.getContentLengthLong()>limit) { ApiProblems.write(res,413,"TRACE_LIMIT_EXCEEDED"); return; }
        var wrapped=new HttpServletRequestWrapper(req) {
            private ServletInputStream bounded;
            @Override public ServletInputStream getInputStream() throws IOException {
                if(bounded!=null) return bounded;
                var source=super.getInputStream();
                bounded=new ServletInputStream() {
                    private long read;
                    private void add(int count) throws TraceInputLimitException { if(count>0 && (read+=count)>limit) throw new TraceInputLimitException(); }
                    @Override public int read() throws IOException { int value=source.read(); if(value!=-1) add(1); return value; }
                    @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                        java.util.Objects.checkFromIndexSize(offset,length,bytes.length);
                        if(length==0) return 0;
                        if(read>limit) throw new TraceInputLimitException();
                        int count=source.read(bytes,offset,(int)Math.min(length,limit-read+1)); add(count); return count;
                    }
                    @Override public boolean isFinished() { return source.isFinished(); }
                    @Override public boolean isReady() { return source.isReady(); }
                    @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
                    @Override public void close() throws IOException { source.close(); }
                };
                return bounded;
            }
            @Override public java.io.BufferedReader getReader() throws IOException { return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
        };
        try { chain.doFilter(wrapped,res); }
        catch(IOException|ServletException failure) {
            Throwable cause=failure;
            while(cause!=null && !(cause instanceof TraceInputLimitException)) cause=cause.getCause();
            if(cause!=null && !res.isCommitted()) { ApiProblems.write(res,413,"TRACE_LIMIT_EXCEEDED"); return; }
            throw failure;
        }
    }
}
