package com.processor;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 有界的后台完成结果队列。
 *
 * <p>bot 离线或轮询停止时，后台完成的视频/图片/投递结果会无限堆积。
 * 本队列在超出容量时丢弃最旧结果，作为内存上限的兜底。</p>
 *
 * <p>只重写 {@code add()}：本项目所有生产者都用 {@code add()} 入队，够用且避免
 * 覆写 {@code offer()} 造成 {@code super.add()} 内部调用递归。</p>
 */
public class BoundedResultQueue extends ConcurrentLinkedQueue<ProcessResult> {

    private final int maxSize;

    public BoundedResultQueue(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    @Override
    public synchronized boolean add(ProcessResult item) {
        if (item == null) {
            return false;
        }
        // 超过容量先丢弃最旧，保持最新结果
        while (size() >= maxSize) {
            poll();
        }
        return super.offer(item);
    }
}
