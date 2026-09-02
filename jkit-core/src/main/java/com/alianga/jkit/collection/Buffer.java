package com.alianga.jkit.collection;

import java.util.Collection;

/**
 * Defines a collection that allows objects to be removed in some well-defined order.
 * The removal order can be based on insertion order (eg, a FIFO queue or a LIFO stack),
 * on access order (eg, an LRU cache), on some arbitrary comparator (eg, a priority queue)
 * or on any other well-defined ordering.
 */
public interface Buffer extends Collection {
    /**
     * Gets and removes the next object from the buffer.
     *
     * @return the next object in the buffer, which is also removed
     * @throws java.nio.BufferUnderflowException if the buffer is already empty
     */
    Object remove();

    /**
     * Gets the next object from the buffer without removing it.
     *
     * @return the next object in the buffer, which is not removed
     * @throws java.nio.BufferUnderflowException if the buffer is empty
     */
    Object get();

}
