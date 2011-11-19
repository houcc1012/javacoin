/**
 * Copyright (C) 2011 NetMind Consulting Bt.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package hu.netmind.bitcoin.block;

import hu.netmind.bitcoin.Block;
import hu.netmind.bitcoin.Transaction;
import hu.netmind.bitcoin.TransactionInput;
import hu.netmind.bitcoin.TransactionOutput;
import hu.netmind.bitcoin.TransactionFilter;
import hu.netmind.bitcoin.BitCoinException;
import hu.netmind.bitcoin.node.p2p.BlockHeader;
import hu.netmind.bitcoin.node.p2p.BitCoinOutputStream;
import hu.netmind.bitcoin.node.p2p.HexUtil;
import hu.netmind.bitcoin.VerificationException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.AbstractList;
import java.math.BigInteger;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Block is a container in which BitCoin transactions are grouped. Generating a
 * Block is a relatively hard computational task that is constantly adjusted so that
 * the whole BitCoin network is able to produce one Block approximately every 10 minutes.
 * When a Miner succeeds in generating a Block it will include all the pending transactions
 * in the network into this Block thereby claiming transaction fees (and generating new coins
 * also). Transactions are considered valid if they are in a Block on a longest path, all other
 * transactions are candidates to include in a next Block. Note: this implementation requires
 * <code>all</code> transactions be supplied if the merkle root hash is not yet calculated,
 * otherwise merkle root can not be calculated. Note: this block implementation does not retain
 * the merkle tree. If transactions are removed later, the root can not be re-calculated. This
 * should not be a problem, if we can trust that all transactions validated already.
 * @author Robert Brautigam
 * TODO: rethink filtering (shouldn't block be immutable?)
 */
public class BlockImpl extends PrefilteredTransactionContainer implements Block
{
   private static final int BLOCK_VERSION = 1;
   private static final long BLOCK_FUTURE_VALIDITY = 2*60*60*1000; // 2 hrs millis
   private static Logger logger = LoggerFactory.getLogger(BlockImpl.class);

   // These are unalterable properties of the block
   private long creationTime;
   private long nonce;
   private long compressedTarget;
   private byte[] previousBlockHash;
   private byte[] merkleRoot;
   private byte[] hash;

   // Transient internal attributes
   private boolean orphan;
   private Difficulty totalDifficulty;
   private long height;
   
   // Below are properties filled runtime (and potentially altered depending on chain changes,
   // or new blocks or filters)
   private List<Transaction> transactions;
   private MerkleTree merkleTree;

   public BlockImpl(List<Transaction> transactions, TransactionFilter preFilter,
         long creationTime, long nonce, long compressedTarget, byte[] previousBlockHash, byte[] merkleRoot)
      throws BitCoinException
   {
      this(transactions,preFilter,creationTime,nonce,compressedTarget,previousBlockHash,merkleRoot,null,null);
   }

   public BlockImpl(List<Transaction> transactions, TransactionFilter preFilter,
         long creationTime, long nonce, long compressedTarget, byte[] previousBlockHash, 
         byte[] merkleRoot, MerkleTree merkleTree, byte[] hash)
      throws BitCoinException
   {
      this.creationTime=creationTime;
      this.nonce=nonce;
      this.compressedTarget=compressedTarget;
      this.previousBlockHash=previousBlockHash;
      this.merkleRoot=merkleRoot;
      this.merkleTree=merkleTree;
      if ( merkleTree == null )
         this.merkleTree = new MerkleTree(transactions);
      this.hash=hash;
      if ( hash == null )
         this.hash = calculateHash();
      setPreFilter(preFilter);
      this.transactions = new LinkedList<Transaction>(transactions);
      prefilterTransactions();
   }

   /**
    * Use the pre-filter to filter the transactions stored in this block. This is done on creation
    * as an initial pre-filtering, but may be called regularly (for example if some filters are time
    * or block depth dependent). As a result the filtered transactions will be removed, the merkle tree
    * will be adjusted (compacted) accordingly.
    */
   void prefilterTransactions()
   {
      if ( getPreFilter() == null )
         return;
      // Run the filtering, but handle removed items
      getPreFilter().filterTransactions(new AbstractList<Transaction>()
            {
               public Transaction get(int index)
               {
                  return transactions.get(index);
               }

               public int size()
               {
                  return transactions.size();
               }

               public Transaction remove(int index)
               {
                  // This is called when a transaction is removed
                  Transaction removedTx = transactions.remove(index);
                  merkleTree.removeTransaction(removedTx);
                  return removedTx;
               }
            });
   }

   /**
    * Get all the stored transactions.
    */
   protected List<Transaction> getStoredTransactions()
   {
      return transactions;
   }

   public List<Transaction> getAvailableTransactions()
   {
      return transactions;
   }

   protected void addStoredTransactions(List<Transaction> transactions)
   {
      throw new UnsupportedOperationException("Can not add transactions to block");
   }

   /**
    * Get the network block header representation of this Block.
    */
   private BlockHeader getBlockHeader()
   {
      return new BlockHeader(BLOCK_VERSION,previousBlockHash,merkleRoot,creationTime,
            compressedTarget,nonce);
   }

   /**
    * Calculate the hash of this block.
    */
   private byte[] calculateHash()
      throws BitCoinException
   {
      try
      {
         BlockHeader blockHeader = getBlockHeader();
         // Now serialize this to byte array
         ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
         BitCoinOutputStream output = new BitCoinOutputStream(byteOutput);
         blockHeader.writeTo(output);
         output.close();
         byte[] blockHeaderBytes = byteOutput.toByteArray();
         if ( logger.isDebugEnabled() )
            logger.debug("hashing block header: {}",HexUtil.toHexString(blockHeaderBytes));
         // Hash this twice
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         byte[] firstHash = digest.digest(blockHeaderBytes);
         digest.reset();
         return digest.digest(firstHash);
      } catch ( NoSuchAlgorithmException e ) {
         throw new BitCoinException("can not find sha-256 algorithm for hash calculation",e);
      } catch ( IOException e ) {
         throw new BitCoinException("failed to calculate hash for block header",e);
      }
   }

   /**
    * Run all validations that require no context.
    */
   public void validate()
      throws VerificationException
   {
      // This method goes over all the rules mentioned at:
      // https://en.bitcoin.it/wiki/Protocol_rules#.22block.22_messages
      
      // 1. Check syntactic correctness 
      //    Done: already done when block is parsed
      // 2. Reject if duplicate of block we have in any of the three categories 
      //    Ommitted: needs context, and depends on the original client implementation
      // 3. Transaction list must be non-empty 
      //    Note: we have to keep coinbase transactions anyway for now, so
      //    one transaction must be there
      if ( transactions.isEmpty() )
         throw new VerificationException("block ("+this+") has no transactions");
      // 4. Block hash must satisfy claimed nBits proof of work 
      DifficultyTarget claimedTarget = new DifficultyTarget(compressedTarget);
      DifficultyTarget hashTarget = new DifficultyTarget(hash);
      if ( hashTarget.compareTo(claimedTarget) > 0 )
         throw new VerificationException("difficulty of block ("+this+") does not have claimed difficulty of: "+claimedTarget);
      // 5. Block timestamp must not be more than two hours in the future 
      if ( creationTime > System.currentTimeMillis() + BLOCK_FUTURE_VALIDITY )
         throw new VerificationException("creation time of block ("+this+"): "+new Date(creationTime)+" is too far in future");
      // 6. First transaction must be coinbase (i.e. only 1 input, with hash=0, n=-1), the rest must not be 
      if ( ! transactions.get(0).isCoinbase() )
         throw new VerificationException("block's first transaction was not coinbase: "+this);
      for ( int i=1; i<transactions.size(); i++ )
         if ( transactions.get(i).isCoinbase() )
            throw new VerificationException("block's "+i+"the transaction is a coinbase, it should only be the first though");
      // 7. For each transaction, apply "tx" checks 2-4 
      //    Note: this does all the non-context aware checks for transactions
      for ( Transaction tx : transactions )
         tx.validate();
      // 8. For the coinbase (first) transaction, scriptSig length must be 2-100 
      //    Ommitted: checked in transaction validate
      // 9. Reject if sum of transaction sig opcounts > MAX_BLOCK_SIGOPS 
      //    Ommitted: transactions already check for script complexity
      // 10. Verify Merkle hash 
      if ( ! Arrays.equals(merkleTree.getRoot(),merkleRoot) )
         throw new VerificationException("block's ("+this+") merkle root does not match transaction hashes");
   }

   public long getCreationTime()
   {
      return creationTime;
   }
   public long getNonce()
   {
      return nonce;
   }
   public long getCompressedTarget()
   {
      return compressedTarget;
   }
   public byte[] getMerkleRoot()
   {
      return merkleRoot;
   }
   public MerkleTree getMerkleTree()
   {
      return merkleTree;
   }
   public byte[] getHash()
   {
      return hash;
   }
   public byte[] getPreviousBlockHash()
   {
      return previousBlockHash;
   }

   public boolean isOrphan()
   {
      return orphan;
   }
   public void setOrphan(boolean orphan)
   {
      this.orphan=orphan;
   }

   public Difficulty getTotalDifficulty()
   {
      return totalDifficulty;
   }
   public void setTotalDifficulty(Difficulty totalDifficulty)
   {
      this.totalDifficulty=totalDifficulty;
   }

   public long getHeight()
   {
      return height;
   }
   public void setHeight(long height)
   {
      this.height=height;
   }

}

