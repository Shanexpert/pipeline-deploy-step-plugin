package org.jenkinsci.plugins.workflow.support.steps.deploy;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.UnknownHostException;

public class ConnectionManager {

    // 最大连接数
    private static final int MAX_TOTAL = 600;
    // 每一个路由的最大连接数
    private static final int MAX_PER_ROUTE = 150;
    //从连接池中获得连接的超时时间
    private static final int CONNECTION_REQUEST_TIMEOUT = 3000;
    //连接超时
    private static final int CONNECTION_TIMEOUT = 3000;
    //获取数据的超时时间
    private static final int SOCKET_TIMEOUT = 60000;

    PoolingHttpClientConnectionManager cm;

    CloseableHttpClient httpClient;

    /**
     * 重连接策略
     */
    HttpRequestRetryHandler retryHandler = new HttpRequestRetryHandler() {
        @Override
        public boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
            if (executionCount >= 3) {
                // Do not retry if over max retry count
                return false;
            }
            if (exception instanceof InterruptedIOException) {
                // Timeout
                return false;
            }
            if (exception instanceof UnknownHostException) {
                // Unknown host
                return false;
            }
            if (exception instanceof ConnectTimeoutException) {
                // Connection refused
                return false;
            }
            if (exception instanceof SSLException) {
                // SSL handshake exception
                return false;
            }
            HttpClientContext clientContext = HttpClientContext.adapt(context);
            HttpRequest request = clientContext.getRequest();
            return !(request instanceof HttpEntityEnclosingRequest);
        }
    };

    /**
     * 配置连接参数
     */
    RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT)
            .setConnectTimeout(CONNECTION_TIMEOUT)
            .setSocketTimeout(SOCKET_TIMEOUT)
            .build();

    public ConnectionManager() {
        cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(MAX_TOTAL);
        cm.setDefaultMaxPerRoute(MAX_PER_ROUTE);

        // 定制实现HttpClient，全局只有一个HttpClient
        httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .setRetryHandler(retryHandler)
                .build();
    }

    public CloseableHttpClient getHttpClient() {
        return httpClient;
    }

    public HttpClientConnectionManager getManager() {
        return cm;
    }

}
