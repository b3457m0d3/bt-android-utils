/*
 * Copyright (c) 2011 Brian J. Tarricone <brian@tarricone.org>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.spurint.android.net;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.protocol.HttpContext;

import android.os.Build;
import android.os.Handler;
import android.util.Log;

public class Network
{
    private static final String TAG = "Network";
    
    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = CORE_POOL_SIZE;
    private static final ExecutorService pool = new ThreadPoolExecutor(CORE_POOL_SIZE,
                                                                       MAX_POOL_SIZE,
                                                                       30, TimeUnit.SECONDS,
                                                                       new LinkedBlockingQueue<Runnable>());
    private static final Handler handler = new Handler();

    public interface RequestListener
    {
        void onRequestFinished(HttpResponse resp);
        void onRequestError(Exception e);
    }

    private static class NetworkTask implements Callable<HttpResponse>
    {
        private HttpClient httpClient;
        private RequestListener listener;
        private HttpUriRequest request;

        NetworkTask(HttpClient httpClient,
                    HttpUriRequest request,
                    RequestListener listener)
        {
            this.httpClient = httpClient;
            this.request = request;
            this.listener = listener;
        }

        @Override
		public HttpResponse call() throws Exception
        {
            HttpResponse resp = null;
            Exception err = null;
            
            if (Thread.interrupted())
            	return null;
            
            try {
                Log.d(TAG, "starting http request");
                resp = httpClient.execute(request);
            } catch (Exception e) {
                err = e;
            }
            
            final HttpResponse finalResp = resp;
            final Exception finalErr = err;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (finalErr != null) {
                        listener.onRequestError(finalErr);
                    } else if (finalResp != null) {
                        Log.d(TAG, "got http response code " + finalResp.getStatusLine().getStatusCode());
                        HttpEntity entity = finalResp.getEntity();
                        Log.d(TAG, "body is type " + entity.getContentType() + ", length " + entity.getContentLength());
                        listener.onRequestFinished(finalResp);
                    } 
                }
            });
            
            return resp;
        }
    }

    private static final int CONN_TIMEOUT = 30000;
    private static final int SO_TIMEOUT = 45000;

    private static final Network instance = new Network();

    private DefaultHttpClient httpClient;

    public static synchronized Network get()
    {
        return instance;
    }

    private Network()
    {
        BasicHttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, CONN_TIMEOUT);
        HttpConnectionParams.setSoTimeout(params, SO_TIMEOUT);

        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", new PlainSocketFactory(), 80));
        try {
            registry.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443)); // new SSLSocketFactory(KeyStore.getInstance(KeyStore.getDefaultType())), 443));
        } catch (Exception e) {
            e.printStackTrace();
        }

        ThreadSafeClientConnManager connManager = new ThreadSafeClientConnManager(params, registry);
        httpClient = new DefaultHttpClient(connManager, params);
        httpClient.addRequestInterceptor(new HttpRequestInterceptor() {
            @Override
            public void process(HttpRequest request, HttpContext context) throws HttpException, IOException
            {
                Locale locale = Locale.getDefault();
                String localeStr = locale.getLanguage() + "_" + locale.getCountry();
                request.setHeader("User-Agent", "BTNetwork (Android; Android OS " + Build.VERSION.RELEASE + "; " + localeStr + ")");
                Header[] headers = request.getAllHeaders();
                for (Header h : headers) {
                    Log.d(TAG, h.getName() + ": " + h.getValue());
                }
            }
        });
    }

    public Future<HttpResponse> execute(final HttpUriRequest request, final RequestListener listener)
    {
        NetworkTask task = new NetworkTask(httpClient, request, listener);
        return pool.submit(task);
    }
}