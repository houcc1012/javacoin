package it.nibbles.javacoin.block;

/**
 *
 * @author Alessandro Polverini <alex@nibbles.it>
 */
public interface StorageSession extends AutoCloseable
{
   @Override
   public void close();
   public void commit();
   public void rollback();
}
