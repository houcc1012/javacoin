/**
 * Copyright (C) 2012 nibbles.it
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package hu.netmind.bitcoin.block;

import hu.netmind.bitcoin.BitcoinException;
import hu.netmind.bitcoin.Block;
import hu.netmind.bitcoin.ScriptFactory;
import hu.netmind.bitcoin.Transaction;
import hu.netmind.bitcoin.TransactionOutput;
import hu.netmind.bitcoin.VerificationException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 *
 * @author Alessandro Polverini
 */
public class ParallelTransactionsVerifier extends BlockTransactionsVerifier
{

   private ExecutorService executorService;
   private BlockChainLink link;
   private Block block;
   private int numThreads;
   private long inValue;
   private long outValue;

   public ParallelTransactionsVerifier(BlockChainLinkStorage linkStorage, ScriptFactory scriptFactory, boolean simplifiedVerification)
   {
      this(linkStorage, scriptFactory, simplifiedVerification, 0);
   }

   public ParallelTransactionsVerifier(BlockChainLinkStorage linkStorage, ScriptFactory scriptFactory, boolean simplifiedVerification, int maxThreads)
   {
      super(linkStorage, scriptFactory, simplifiedVerification);
      numThreads = maxThreads <= 0 ? Runtime.getRuntime().availableProcessors() : maxThreads;
      executorService = Executors.newFixedThreadPool(numThreads, new DaemonThreadFactory());
      logger.info("Parallel Transaction Verifier instantiated with {} threads", numThreads);
   }

   @Override
   public long verifyBlockTransactions(BlockChainLink previousLink, Block block)
      throws VerificationException, BitcoinException
   {
      this.link = previousLink;
      this.block = block;
      inValue = outValue = 0;
      logger.debug("Parallel checking of {} transactions...", block.getTransactions().size());
      List<Callable<Void>> todo = new ArrayList<>(block.getTransactions().size());
      for (Transaction tx : block.getTransactions())
      {
         todo.add(new SingleTxVerifier(tx));
      }
      try
      {
         List<Future<Void>> allRes = executorService.invokeAll(todo);
         for (Iterator<Future<Void>> it = allRes.iterator(); it.hasNext();)
         {
            Future<Void> res = it.next();
            try
            {
               res.get();
            } catch (ExecutionException ex)
            {
               Throwable t = ex.getCause();
               if (t instanceof VerificationException)
                  throw (VerificationException) t;
               else
               {
                  logger.error("Unexpected error: " + t.getMessage(), t);
                  throw new BitcoinException("Unexpected exception while veryfing block", t);
               }
            }
         }
      } catch (InterruptedException ex)
      {
         executorService.shutdownNow();
         executorService = Executors.newFixedThreadPool(numThreads);
         throw new VerificationException("Verification threads interrupted");
      }
      return inValue - outValue;
   }

   class SingleTxVerifier implements Callable<Void>
   {

      private Transaction tx;

      public SingleTxVerifier(Transaction tx)
      {
         this.tx = tx;
      }

      @Override
      public Void call() throws VerificationException
      {
         InOutValues inOutValues = new InOutValues();
         // Validate without context
         tx.validate();
         // Checks 16.1.1-7: Verify only if this is supposed to be a full node
         long localOutValue = 0;
         if ((!simplifiedVerification) && (!tx.isCoinbase()))
         {
            long localInValue = verifyTransaction(link, block, tx);
            for (TransactionOutput out : tx.getOutputs())
            {
               localOutValue += out.getValue();
            }
            synchronized (this)
            {
               inValue += localInValue;
               outValue += localOutValue;
            }
            // Check 16.1.6: Using the referenced output transactions to get
            // input values, check that each input value, as well as the sum, are in legal money range
            // Check 16.1.7: Reject if the sum of input values < sum of output values
            if (localInValue < localOutValue)
               throw new VerificationException("more money spent (" + localOutValue + ") then available (" + localInValue + ") in transaction: " + tx);
         }
         return null;
      }
   }

   class DaemonThreadFactory implements ThreadFactory
   {

      int counter = 0;

      @Override
      public Thread newThread(Runnable r)
      {
         counter++;
         Thread t = new Thread(r);
         t.setDaemon(true);
         t.setName("Transaction Verifier " + counter);
         return t;
      }
   }

   class InOutValues
   {

      public long inValue = 0;
      public long outValue = 0;
   }
}
