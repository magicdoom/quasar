/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.strands.queues;

import co.paralleluniverse.common.util.Objects;

/**
 *
 * @author pron
 */
abstract class SingleConsumerLinkedWordQueue<E> extends SingleConsumerLinkedQueue<E> {
    @Override
    Node newNode() {
        return new WordNode();
    }

    boolean enq(int item) {
        WordNode node = new WordNode();
        node.value = item;
        return enq(node);
    }

    int rawValue(Node node) {
        return ((WordNode) node).value;
    }

    @Override
    void clearValue(Node node) {
    }

    static class WordNode extends Node {
        int value;

        @Override
        public String toString() {
            return "Node{" + "value: " + value + ", next: " + next + ", prev: " + Objects.systemToString(prev) + '}';
        }
    }
}