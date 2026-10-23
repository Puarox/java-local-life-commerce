package com.hmall.gateway.filter;

import com.hmall.common.exception.UnauthorizedException;
import com.hmall.gateway.config.AuthProperties;
import com.hmall.gateway.util.JwtTool;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {


    private final AuthProperties authProperties;
    private final JwtTool jwtTool;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //1 get the request
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.remove("user-info"))
                .build();
        exchange = exchange.mutate().request(request).build();
        // Public reads can still carry an optional JWT so likes and profile state stay accurate.
        boolean publicRoute = isExclude(request.getMethod(), request.getPath().toString());
        String token = null ;
        List<String> headers =  request.getHeaders().get("authorization");
        if(headers != null && !headers.isEmpty() ){
            token = headers.get(0);
        }
        if (publicRoute && token == null) return chain.filter(exchange);
        Long userId = null;
        try {
             userId = jwtTool.parseToken(token);
        }catch (UnauthorizedException e){
            if (publicRoute) return chain.filter(exchange);
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }
        // 5 keep the authenticated user and pass it to the service
        String userInfo =userId.toString();
        ServerWebExchange ex = exchange.mutate()
                .request(b -> b.headers(h -> h.set("user-info", userInfo)))
                .build();
        return chain.filter(ex);
    }

    private boolean isExclude(HttpMethod method, String path) {
        if (HttpMethod.POST.equals(method)) {
            return "/users/login".equals(path) || "/users/code".equals(path);
        }
        if (!HttpMethod.GET.equals(method)) return false;
        for (String pathPattern : authProperties.getExcludePaths()) {
            if(antPathMatcher.match(pathPattern, path)){
                return true;
            }
        }
        return false ;
    }

    @Override
    public int getOrder() {
        return 0 ;
    }
}
