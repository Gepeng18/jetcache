/**
 * Created on  13-09-09 15:59
 */
package com.taobao.geek.jetcache.impl;

import com.taobao.geek.jetcache.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * @author <a href="mailto:yeli.hl@taobao.com">huangli</a>
 */
class CacheHandler implements InvocationHandler {

    private Object src;
    private CacheProviderFactory cacheProviderFactory;

    private CacheInvokeConfig cacheInvokeConfig;
    private HashMap<String, CacheInvokeConfig> configMap;

    public CacheHandler(Object src, CacheConfig cacheConfig, CacheProviderFactory cacheProviderFactory) {
        this.src = src;
        cacheInvokeConfig = new CacheInvokeConfig();
        cacheInvokeConfig.cacheConfig = cacheConfig;
        cacheInvokeConfig.conditionEL = ExpressionUtil.parseEL(cacheConfig.getCondition());
        cacheInvokeConfig.unlessEL = ExpressionUtil.parseEL(cacheConfig.getUnless());
        this.cacheProviderFactory = cacheProviderFactory;
    }

    public CacheHandler(Object src, HashMap<String, CacheInvokeConfig> configMap, CacheProviderFactory cacheProviderFactory) {
        this.src = src;
        this.configMap = configMap;
        this.cacheProviderFactory = cacheProviderFactory;
    }

    @Override
    public Object invoke(Object proxy, final Method method, final Object[] args) throws Throwable {
        CacheInvokeContext context = null;
        if (cacheInvokeConfig != null) {
            context = new CacheInvokeContext();
            context.cacheInvokeConfig = cacheInvokeConfig;
        } else {
            String sig = ClassUtil.getMethodSig(method);
            CacheInvokeConfig cac = configMap.get(sig);
            if (cac != null) {
                context = new CacheInvokeContext();
                context.cacheInvokeConfig = cac;
            }
        }
        if (context == null) {
            return method.invoke(src, args);
        } else {
            context.args = args;
            context.cacheProviderFactory = cacheProviderFactory;
            context.method = method;
            context.target = src;
            return invoke(context);


        }
    }

    public static Object invoke(CacheInvokeContext context) throws Throwable {
        if (context.cacheInvokeConfig.enableCacheContext) {
            try {
                CacheContextSupport.enable();
                return doInvoke(context);
            } finally {
                CacheContextSupport.disable();
            }
        } else {
            return doInvoke(context);
        }
    }

    public static Object doInvoke(CacheInvokeContext context) throws Throwable{
        CacheConfig cacheConfig = context.cacheInvokeConfig.cacheConfig;
        if (cacheConfig != null && (cacheConfig.isEnabled() || CacheContextSupport.isEnabled())) {
            return invokeWithCache(context);
        } else {
            return invokeOrigin(context);
        }
    }

    private static Object invokeWithCache(CacheInvokeContext context)
            throws Throwable {
        if (!ExpressionUtil.evalCondition(context)) {
            return invokeOrigin(context);
        }

        CacheConfig cacheConfig = context.cacheInvokeConfig.cacheConfig;

        CacheProvider cacheProvider = context.cacheProviderFactory.getCache(cacheConfig.getArea());
        String subArea = ClassUtil.getSubArea(cacheConfig, context.method);
        String key = cacheProvider.getKeyGenerator().getKey(context.args);
        boolean hit = getFromCache(context, cacheProvider, subArea, key);

        context.needUpdateLocal = false;
        context.needUpdateRemote = false;

        if (!hit) {
            context.result = invokeOrigin(context);
            if (ExpressionUtil.evalUnless(context)) {
                context.needUpdateLocal = needUpdate(context.localResult);
                context.needUpdateRemote = needUpdate(context.remoteResult);
            } else {
                return context.result;
            }
        } else if (context.result == null && !cacheConfig.isCacheNullValue()) {
            context.result = invokeOrigin(context);
            if (ExpressionUtil.evalUnless(context)) {
                context.needUpdateLocal = context.localResult != null && context.localResult != CacheResultCode.FAIL;
                context.needUpdateRemote = context.remoteResult != null && context.remoteResult != CacheResultCode.FAIL;
            } else {
                return context.result;
            }
        } else {
            if (ExpressionUtil.evalUnless(context)) {
                context.needUpdateLocal = needUpdate(context.localResult);
                context.needUpdateRemote = false;
            } else {
                context.result = invokeOrigin(context);
                if (ExpressionUtil.evalUnless(context)) {
                    context.needUpdateLocal = context.localResult != null;
                    context.needUpdateRemote = context.remoteResult != null;
                } else {
                    return context.result;
                }
            }
        }

        updateCache(context, cacheProvider, subArea, key);
        return context.result;
    }

    private static void updateCache(CacheInvokeContext context, CacheProvider cacheProvider, String subArea, String key) {
        context.localResult = null;
        context.remoteResult = null;
        CacheConfig cacheConfig = context.cacheInvokeConfig.cacheConfig;
        if (context.needUpdateLocal) {
            context.localResult = cacheProvider.getLocalCache().put(cacheConfig, subArea, key, context.result);
        }
        if (context.needUpdateRemote) {
            context.remoteResult = cacheProvider.getRemoteCache().put(cacheConfig, subArea, key, context.result);
        }
        if (context.cacheProviderFactory.getCacheMonitor() != null && (context.localResult != null || context.remoteResult != null)) {
            context.cacheProviderFactory.getCacheMonitor().onPut(cacheConfig, subArea, key, context.result, context.localResult, context.remoteResult);
        }
    }

    private static boolean getFromCache(CacheInvokeContext context, CacheProvider cacheProvider, String subArea, String key) {
        CacheConfig cacheConfig = context.cacheInvokeConfig.cacheConfig;
        if (cacheConfig.getCacheType() == CacheType.REMOTE) {
            CacheResult result = cacheProvider.getRemoteCache().get(cacheConfig, subArea, key);
            context.remoteResult = result.getResultCode();
            if (result.isSuccess()) {
                context.result = result.getValue();
            }
        } else {
            CacheResult result = cacheProvider.getLocalCache().get(cacheConfig, subArea, key);
            context.localResult = result.getResultCode();
            if (result.isSuccess()) {
                context.result = result.getValue();
            } else {
                if (cacheConfig.getCacheType() == CacheType.BOTH) {
                    result = cacheProvider.getRemoteCache().get(cacheConfig, subArea, key);
                    context.remoteResult = result.getResultCode();
                    if (result.isSuccess()) {
                        context.result = result.getValue();
                    }
                }
            }
        }
        if (context.cacheProviderFactory.getCacheMonitor() != null) {
            context.cacheProviderFactory.getCacheMonitor().onGet(cacheConfig, subArea, key, context.localResult, context.remoteResult);
        }

        return context.localResult == CacheResultCode.SUCCESS || context.remoteResult == CacheResultCode.SUCCESS;
    }

    private static boolean needUpdate(CacheResultCode code) {
        return code != null && (code == CacheResultCode.NOT_EXISTS || code == CacheResultCode.EXPIRED);
    }

    private static Object invokeOrigin(CacheInvokeContext context) throws Throwable {
        if (context.invoker == null) {
            return context.method.invoke(context.target, context.args);
        } else {
            return context.invoker.invoke();
        }
    }


}
