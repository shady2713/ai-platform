package com.basicframework.framework.ai.core.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 有界流式响应：{@link ExternalHttpStreamResponse} 的实现（F11）。
 *
 * <p>响应体上限在这里**读一块判一块**：每块最多读"剩余额度 + 1"字节，多出的那 1 字节只用于判定超限、
 * 不会交付给调用方，因此 {@link #deliveredBytes()} 恒不超过 {@code max-response-bytes}。超限时以稳定原因
 * {@link ExternalHttpException.Reason#RESPONSE_TOO_LARGE} 终止流并关闭上游连接——既不静默截断，
 * 也不"先读完再整体拒绝"。
 *
 * <p>与请求/响应入口的差异仅此一处：{@code readBounded} 靠声明长度与一次性读取判定，本类靠累计交付量判定。
 * 声明长度对流式响应只作为诊断信息（分块传输的 SSE 根本没有声明长度），不用于提前拒绝，
 * 否则长流会退化成 M07 记录的"整体拒绝"。
 */
final class BoundedExternalHttpStreamResponse implements ExternalHttpStreamResponse {

    private final int status;

    private final Map<String, String> headers;

    private final long declaredLength;

    private final int limit;

    private final InputStream body;

    private final AtomicBoolean closed = new AtomicBoolean();

    /** 已交付字节数：只由读取线程写，读方单线程消费，不需要同步。 */
    private long delivered;

    /** 上游流是否已读尽。 */
    private boolean exhausted;

    BoundedExternalHttpStreamResponse(
            int status, Map<String, String> headers, long declaredLength, int limit, InputStream body) {
        this.status = status;
        this.headers = headers;
        this.declaredLength = declaredLength;
        this.limit = limit;
        this.body = body;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public long declaredLength() {
        return declaredLength;
    }

    @Override
    public long deliveredBytes() {
        return delivered;
    }

    @Override
    public int readChunk(byte[] target) {
        if (target == null || target.length == 0) {
            throw new IllegalArgumentException("读取缓冲不能为空");
        }
        if (closed.get() || exhausted) {
            return -1;
        }
        long remaining = limit - delivered;
        // 额度已用尽时仍读 1 字节：只有确认上游确实还有内容才判超限，否则流到此为止是正常结束
        int capacity = (int) Math.min(target.length, Math.max(remaining + 1, 1L));
        int read;
        try {
            read = body.read(target, 0, capacity);
        } catch (IOException exception) {
            close();
            throw GuardedExternalHttpClient.mapFailure(exception);
        }
        if (read < 0) {
            exhausted = true;
            return -1;
        }
        if (read == 0) {
            return 0;
        }
        if (read > remaining) {
            // 越界的这一块不交付：已交付量恒不超过上限，且异常在"已交付 N 字节"之后才发生
            close();
            throw new ExternalHttpException(ExternalHttpException.Reason.RESPONSE_TOO_LARGE, "流式响应超过上限");
        }
        delivered += read;
        return read;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                body.close();
            } catch (IOException ignored) {
                // 取消/终止路径上的关闭失败不影响拒绝与终止语义
            }
        }
    }
}
