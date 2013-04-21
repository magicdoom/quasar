/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.strands.queues;

/**
 *
 * @author pron
 */
abstract class SingleConsumerLinkedArrayWordQueue<E> extends SingleConsumerLinkedArrayPrimitiveQueue<E> {
    public static final int BLOCK_SIZE = 8;

    @Override
    int blockSize() {
        return BLOCK_SIZE;
    }

    boolean enq(int item) {
        ElementPointer ep = preEnq();
        ((WordNode) ep.n).array[ep.i] = item;
        postEnq(ep.n, ep.i);
        return true;
    }

    int rawValue(Node n, int i) {
        return ((WordNode) n).array[i];
    }

    @Override
    Node newNode() {
        return new WordNode();
    }

    private static class WordNode extends PrimitiveNode {
        final int[] array = new int[BLOCK_SIZE];
    }
}