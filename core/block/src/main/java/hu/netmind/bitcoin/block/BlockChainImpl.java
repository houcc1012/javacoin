/**
 * Copyright (C) 2011 NetMind Consulting Bt.
 * Copyright (C) 2012 nibbles.it.
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
import hu.netmind.bitcoin.BlockChain;
import hu.netmind.bitcoin.Script;
import hu.netmind.bitcoin.ScriptException;
import hu.netmind.bitcoin.ScriptFactory;
import hu.netmind.bitcoin.Transaction;
import hu.netmind.bitcoin.TransactionInput;
import hu.netmind.bitcoin.TransactionOutput;
import hu.netmind.bitcoin.VerificationException;
import it.nibbles.bitcoin.utils.BtcUtil;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Observable;
import java.util.ResourceBundle;
import java.util.StringTokenizer;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The BlockChain is responsible for maintaining the list of valid Blocks
 * and also calculating the longest chain starting from the Genesis Block.
 * @author Robert Brautigam, Alessandro Polverini
 */
public class BlockChainImpl extends Observable implements BlockChain
{
   private static final Logger logger = LoggerFactory.getLogger(BlockChainImpl.class);
   public static final long TARGET_TIMESPAN = 14l*24l*60l*60l*1000l; // A target lasts 14 days
   public static final long TARGET_SPACING = 10l*60l*1000l; // Spacing between two blocks 10 minutes
   public static final long TARGET_RECALC = TARGET_TIMESPAN / TARGET_SPACING;
   public static final int MEDIAN_BLOCKS = 11;
   public static final long COINBASE_MATURITY = 100;
   public static final long INITIAL_COINBASE_VALUE = 5000000000l;
   public static final long COINBASE_VALUE_HALFTIME = 210000l;

   private static final Map<BigInteger,Map<Long,BigInteger>> knownHashes =
      new HashMap<>();

   private OrphanBlocksSet orphanBlocks = new OrphanBlocksSet();
   private Block genesisBlock = null;
   private BlockChainLinkStorage linkStorage = null;
   private BlockChainListener listener = null;
   private ScriptFactory scriptFactory = null;
   private boolean simplifedVerification = false;
   private boolean isTestnet = false;

   /**
    * Construct a new block chain.
    * @param genesisBlock The valid genesis block for this chain.
    * @param linkStorage The store to get/store the chain links.
    * @param simplifedVerification Set to "true" to disable transaction checking. If this
    * is disabled the bitcoin network (whoever supplies blocks) is trusted instead. You have to
    * disable this check if you don't want to run a full node.
    */
   public BlockChainImpl(Block genesisBlock, BlockChainLinkStorage linkStorage,
         ScriptFactory scriptFactory, boolean simplifedVerification)
      throws VerificationException
   {
      this(genesisBlock, linkStorage, scriptFactory, simplifedVerification, false);
   }
   
   /**
    * Construct a new block chain.
    * @param genesisBlock The valid genesis block for this chain.
    * @param linkStorage The store to get/store the chain links.
    * @param simplifedVerification Set to "true" to disable transaction checking. If this
    * is disabled the bitcoin network (whoever supplies blocks) is trusted instead. You have to
    * disable this check if you don't want to run a full node.
    * @param isTestnet set to true to handle different difficulty and validation rules of the test network
    */
   public BlockChainImpl(Block genesisBlock, BlockChainLinkStorage linkStorage,
         ScriptFactory scriptFactory, boolean simplifedVerification, boolean isTestnet)
      throws VerificationException
   {
      this.linkStorage=linkStorage;
      this.scriptFactory=scriptFactory;
      this.simplifedVerification=simplifedVerification;
      this.genesisBlock=genesisBlock;
      this.isTestnet = isTestnet;
      // Check if the genesis blocks equal, or add genesis block if storage is empty.
      // Here we assume that the storage is not tampered!
      BlockChainLink storedGenesisLink = linkStorage.getGenesisLink();
      if ( storedGenesisLink == null )
      {
         BlockChainLink genesisLink = new BlockChainLink(genesisBlock,
               new Difficulty(new DifficultyTarget(genesisBlock.getCompressedTarget()), isTestnet),BlockChainLink.ROOT_HEIGHT,false);
         linkStorage.addLink(genesisLink);
      } else {
         if ( ! storedGenesisLink.getBlock().equals(genesisBlock) )
            throw new VerificationException("genesis block in storage is not the same as the block chain's");
      }
   }

   public void setListener(BlockChainListener listener)
   {
      this.listener=listener;
   }
   public BlockChainListener getListener()
   {
      return listener;
   }

   @Override
   public Block getBlock(byte[] hash)
   {
      BlockChainLink link = linkStorage.getLink(hash);
      if ( link == null )
         return null;
      return link.getBlock();
   }

   /**
    * Get the previous block.
    */
   @Override
   public Block getPreviousBlock(Block current)
   {
      return getBlock(current.getPreviousBlockHash());
   }

   /**
    * Get the next block.
    */
   @Override
   public Block getNextBlock(Block current, Block target)
   {
      BlockChainLink link = linkStorage.getNextLink(current.getHash(),target.getHash());
      if ( link == null )
         return null;
      return link.getBlock();
   }

   /**
    * Add a block to the chain. The block is only added if it is verified, and
    * passes all known checks. If the block already exists in the chain, nothing
    * is done (there are no changes). Note: orphan blocks are not fully checked
    * when letting into the store, but they will not be cleaned when it turns out
    * they are not valid (so they can't be repeated). Potential DOS attack vector.
    */
   @Override
   public void addBlock(Block block)
      throws VerificationException
   {
      addBlock(block,true);
   }

   /**
    * Compute the latest common block for the two blocks given.
    * @return The latest common block if there is one, or null if
    * there is no common block, in which case one or both blocks
    * must be not of this chain.
    */
   @Override
   public Block getCommonBlock(Block first, Block second)
   {
      BlockChainLink link = linkStorage.getCommonLink(first.getHash(),second.getHash());
      if ( link == null )
         return null;
      return link.getBlock();
   }

   /**
    * Determine whether a block given is reachable from an intermediate
    * block by only going forward. In other words, they are both on this
    * chain, and both are on the same branch also.
    * @param target The block to reach.
    * @param source The source from which the target is attempted to be reached.
    * @return True if the target can be reached from the source, false otherwise.
    * A block can always reach itself. All blocks in the chain are reachable from
    * the genesis block.
    */
   @Override
   public boolean isReachable(Block target, Block source)
   {
      return linkStorage.isReachable(target.getHash(),source.getHash());
   }

   /**
    * Add a block to the chain. If the block is not connectable it will be added to the orphan pool.
    * No orphan is ever passed to the store.
    * @param rawBlock The block to add.
    * @param checkOrphans Whether we should check if some orphans are now connectable.
    */
   private int addBlock(Block block, boolean checkOrphans)
      throws VerificationException
   {
      logger.debug("Trying to add block: {}", block);

      // Internal validation
      block.validate();

      logger.debug("Checking whether block is already in the chain...");
      BlockChainLink link = linkStorage.getLink(block.getHash());
      if (link != null)
         return 0;

      // Check 11: Check whether block is orphan block, in which case notify
      // listener to try to get that block and stop
      logger.debug("Checking whether block is orphan...");
      BlockChainLink previousLink = linkStorage.getLink(block.getPreviousBlockHash());
      if (previousLink == null)
      {
         orphanBlocks.addBlock(block);

         // Notify listeners that we have a missing block
         if (listener != null)
            listener.notifyMissingBlock(block.getPreviousBlockHash());
         // Finish here for now, this block will be re-checked as soon as
         // its parent will be non-orphan
         return 0;
      }

      // Check 12: Check that nBits value matches the difficulty rules
      logger.debug("checking whether block has the appropriate target...");
      DifficultyTarget blockTarget = new DifficultyTarget(block.getCompressedTarget());
      Difficulty blockDifficulty = new Difficulty(blockTarget, isTestnet);
      link = new BlockChainLink(block, // Create link for block
         previousLink.getTotalDifficulty().add(blockDifficulty),
         previousLink.getHeight() + 1, false);
      DifficultyTarget calculatedTarget = getNextDifficultyTarget(previousLink, link);
      if (blockTarget.compareTo(calculatedTarget) != 0)
         // Target has to exactly match the one calculated, otherwise it is
         // considered invalid!
         throw new VerificationException("block has wrong target " + blockTarget
            + ", when calculated is: " + calculatedTarget);
      // Check 13: Reject if timestamp is before the median time of the last 11 blocks
      long medianTimestamp = getMedianTimestamp(previousLink);
      logger.debug("checking timestamp {} against median {}", block.getCreationTime(), medianTimestamp);
      if (block.getCreationTime() <= medianTimestamp)
         throw new VerificationException("block's creation time (" + block.getCreationTime()
            + ") is not after median of previous blocks: " + medianTimestamp);
      // Check 14: Check for known hashes
      BigInteger genesisHash = new BigInteger(1, genesisBlock.getHash());
      BigInteger blockHash = new BigInteger(1, block.getHash());
      if (knownHashes.containsKey(genesisHash))
      {
         BigInteger knownHash = knownHashes.get(genesisHash).get(link.getHeight());
         if ((knownHash != null) && (!knownHash.equals(blockHash)))
            throw new VerificationException("block should have a hash we already know, but it doesn't, might indicate a tampering or attack at depth: " + link.getHeight());
      } else
         logger.warn("known hashes don't exist for this chain, security checks for known blocks can not be made");
      // Checks 15,16,17,18: Check the transactions in the block
      // We diverge from the official list here since we don't maintain main and side branches
      // separately, and we have to make sure block is 100% compliant if we want to add it to the
      // tree (as non-orphan). Because of Block checks we know the first is a coinbase tx and
      // the rest are not. So execute checks from point 16. (Checks 16.3-5 are not
      // handles since they don't apply to this model)
      long time = System.currentTimeMillis();
      logger.debug("checking transactions...");
      long inValue = 0;
      long outValue = 0;
      for (Transaction tx : block.getTransactions())
      {
         // Validate without context
         tx.validate();
         // Checks 16.1.1-7: Verify only if this is supposed to be a full node
         long localInValue = 0;
         long localOutValue = 0;
         if ((!simplifedVerification) && (!tx.isCoinbase()))
         {
            localInValue = verifyTransaction(previousLink, block, tx);
            for (TransactionOutput out : tx.getOutputs())
            {
               localOutValue += out.getValue();
            }
            inValue += localInValue;
            outValue += localOutValue;
            // Check 16.1.6: Using the referenced output transactions to get
            // input values, check that each input value, as well as the sum, are in legal money range
            // Check 16.1.7: Reject if the sum of input values < sum of output values
            if (localInValue < localOutValue)
               throw new VerificationException("more money spent (" + localOutValue + ") then available (" + localInValue + ") in transaction: " + tx);
         }
      }
      
      try
      {
         long time1 = System.currentTimeMillis() - time;
         time = System.currentTimeMillis();
         long originalDiff = inValue - outValue;
         ParallelTransactionVerifier newVerifier =
            new ParallelTransactionVerifier(linkStorage, scriptFactory, previousLink, block, simplifedVerification, 0);
         long newDiff = newVerifier.verifyTransactions();
         long time2 = System.currentTimeMillis() - time;
         if (originalDiff != newDiff)
            throw new VerificationException("Calcolo fee non corrispondente: " + originalDiff + " vs " + newDiff);
         else
            logger.info("time1: " + time1 + " time2: " + time2);
      } catch (Exception e)
      {
         logger.error("Ex in nuovo codice verifica transazioni: " + e.getMessage(), e);
         throw new VerificationException(e.getMessage(), e);
      }
      
      // Verify coinbase if we have full verification and there is a coinbase
      logger.debug("verifying coinbase...");
      Transaction coinbaseTx = null;
      if (!block.getTransactions().isEmpty())
         coinbaseTx = block.getTransactions().get(0);
      if ((!simplifedVerification) && (coinbaseTx.isCoinbase()))
      {
         long coinbaseValue = 0;
         for (TransactionOutput out : coinbaseTx.getOutputs())
         {
            coinbaseValue += out.getValue();
         }
         // Check 16.2: Verify that the money produced is in the legal range
         // Valid if coinbase value is not greater than mined value plus fees in tx
         long coinbaseValid = getBlockCoinbaseValue(link);
         if (coinbaseValue > coinbaseValid + (inValue - outValue))
            throw new VerificationException("coinbase transaction in block " + block + " claimed more coins than appropriate: "
               + coinbaseValue + " vs. " + (coinbaseValid + (inValue - outValue))
               + " (coinbase: " + coinbaseValid + ", in: " + inValue + ", out: " + outValue + ")");
      }

      // Check 16.6: Relay block to our peers
      // (Also: add or update the link in storage, and only relay if it's really new)
      logger.debug("adding block to storage...");
      linkStorage.addLink(link);
      if (listener != null)
         listener.notifyAddedBlock(block);

      // Check 19: For each orphan block for which this block is its prev,
      // run all these steps (including this one) recursively on that orphan
      int blocksAdded = 1;
      if (checkOrphans)
      {
         blocksAdded += connectOrphanBlocks(block);
      }
      return blocksAdded;
   }
   
   /**
    * This is an iterative function that adds to the chain all
    * the orphan blocks that can be added after a new block is inserted
    * to avoid the otherwise more natural recursion.
    * @param lastAddedBlock
    * @return Number of blocks added to the chain
    */
   private int connectOrphanBlocks(Block lastAddedBlock)
   {
      logger.debug("Checking for connectable orphans...");
      int blocksAdded = 0;
      List<Block> blocks = new LinkedList<>();
      blocks.add(lastAddedBlock);
      Block orphan;
      while (!blocks.isEmpty())
      {
         Block block = blocks.remove(0);
         while ((orphan = orphanBlocks.removeBlockByPreviousHash(block.getHash())) != null)
         {
            blocks.add(orphan);
            try
            {
               int n = addBlock(orphan, false);
               if (n != 1)
                  logger.error("Internal error: inserting connectable orphan block returned " + n + " instead of 1");
               blocksAdded++;
            } catch (VerificationException e)
            {
               logger.warn("orphan block was rechecked (because parent appeared), but is not valid", e);
            }
         }
      }
      return blocksAdded;
   }

   /**
    * Get a Block's maximum coinbase value.
    */
   private long getBlockCoinbaseValue(BlockChainLink link)
   {
      return (INITIAL_COINBASE_VALUE) >> (link.getHeight()/COINBASE_VALUE_HALFTIME);
   }

   /**
    * Search for a specific transaction in a block.
    * @param block The block to search transaction in.
    * @param txHash The transaction hash to match.
    * @return The transaction in the block which has the given hash, null otherwise.
    */
   private Transaction getTransaction(Block block, byte[] txHash)
   {
      for ( Transaction txCandidate : block.getTransactions() )
         if ( Arrays.equals(txCandidate.getHash(),txHash) )
            return txCandidate;
      return null;
   }

   /**
    * Verify that a transaction is valid according to sub-rules applying to the block
    * tree.
    * @param link The link that represents the branch if the new transaction.
    * @param block The block we're trying to add.
    * @return The total value of the inputs after verification.
    */
   private long verifyTransaction(BlockChainLink link, Block block, Transaction tx)
      throws VerificationException
   {
      long value = 0;
      for ( TransactionInput in : tx.getInputs() )
      {
         // Check 16.1.1: For each input, look in the [same] branch to find the 
         // referenced output transaction. Reject if the output transaction is missing for any input. 
         Transaction outTx = null;
         BlockChainLink outLink = linkStorage.getPartialClaimedLink(link, in);
         if ( outLink != null ) // Check in chain before
            outTx = getTransaction(outLink.getBlock(),in.getClaimedTransactionHash());
         if ( outTx == null ) // Check in this block if not yet found
            outTx = getTransaction(block,in.getClaimedTransactionHash());
         if ( outTx == null )
            throw new VerificationException("transaction output not found for input: "+in);
         // Check 16.1.2: For each input, if we are using the nth output of the 
         // earlier transaction, but it has fewer than n+1 outputs, reject. 
         if ( outTx.getOutputs().size() <= in.getClaimedOutputIndex() )
            throw new VerificationException("transaction output index for input is out of range: "+
                  (in.getClaimedOutputIndex()+1)+" vs. "+outTx.getOutputs().size());
         // Check 16.1.3: For each input, if the referenced output transaction is coinbase,
         // it must have at least COINBASE_MATURITY confirmations; else reject. 
         if ( (outTx.isCoinbase()) && (outLink.getHeight()+COINBASE_MATURITY > link.getHeight()) )
            throw new VerificationException("input ("+in+") referenced coinbase transaction "+
               outTx+" which was not mature enough (only "+(link.getHeight()-outLink.getHeight()+1)+" blocks before)");
         // Check 16.1.4: Verify crypto signatures for each input; reject if any are bad 
         TransactionOutput out = outTx.getOutputs().get(in.getClaimedOutputIndex());
         value += out.getValue(); // Remember value that goes in from this out
         try
         {
            Script script = scriptFactory.createScript(in.getSignatureScript(), out.getScript());
            if ( ! script.execute(in) )
               throw new VerificationException("verification script for input "+in+" returned 'false' for verification, script was: "+
                  script+" in tx "+BtcUtil.hexOut(tx.getHash()));
         } catch ( ScriptException e ) {
            throw new VerificationException("verification script for input "+in+" in tx "+BtcUtil.hexOut(tx.getHash())+" failed to execute",e);
         }
         // Check 16.1.5: For each input, if the referenced output has already been
         // spent by a transaction in the [same] branch, reject 
         if ( linkStorage.outputClaimedInSameBranch(link, in))
            throw new VerificationException("Block: "+BtcUtil.hexOut(block.getHash())+" Tx: "+BtcUtil.hexOut(tx.getHash())+
               " output claimed by "+in+" is already claimed in another block of the same branch: "+
               BtcUtil.hexOut(linkStorage.getClaimerLink(link, in).getBlock().getHash()));
      }
      return value;
   }

   /**
    * Calculate the median of the (some number of) blocks starting at the given block.
    */
   private long getMedianTimestamp(BlockChainLink link)
   {
      if ( link == null )
         return 0;
      Block block = link.getBlock();
      List<Long> times = new LinkedList<>();
      for ( int i=0; (block!=null) && (i<MEDIAN_BLOCKS); i++ )
      {
         times.add(block.getCreationTime());
         block=getPreviousBlock(block);
      }
      Collections.sort(times);
      return times.get(times.size()/2);
   }

   /**
    * Calculate the difficulty for the next block after the one supplied.
    */
   public DifficultyTarget getNextDifficultyTarget(BlockChainLink link, BlockChainLink newLink)
   {
      // If we're calculating for the genesis block return
      // fixed difficulty
      if ( link == null )
         return isTestnet ? DifficultyTarget.MAX_TESTNET_TARGET : DifficultyTarget.MAX_PRODNET_TARGET;
      // Look whether it's time to change the difficulty setting
      // (only change every TARGET_RECALC blocks). If not, return the
      // setting of this block, because the next one has to have the same
      // target.
      if ( (link.getHeight()+1) % TARGET_RECALC != 0 )
      {
         // Special rules for testnet after 15 Feb 2012
         Block currBlock = link.getBlock();
         if (isTestnet && currBlock.getCreationTime() > 1329180000000L) {
            long timeDiff = newLink.getBlock().getCreationTime()-currBlock.getCreationTime();
            // If the new block's timestamp is more than 2* 10 minutes
            // then allow mining of a min-difficulty block.
            // official client as 0.6.2 has a bug that if date of new block is previous to last one it resets difficulty
            // so we have to "implement" the bug, too, checking if timeDiff is negative
            // TODO: fix this bug when official client fixes it and testnet is reset
            if (timeDiff < 0 || timeDiff > 2*TARGET_SPACING)
               return DifficultyTarget.MAX_TESTNET_TARGET;
            else
            {
               // Return the last non-special-min-difficulty-rules-block
               // We could use a custom method here to load only block headers instead of full blocks
               // but this lack of performance is only for the testnet so we don't care
               while (link != null && (link.getHeight() % TARGET_RECALC) != 0 && 
                  link.getBlock().getCompressedTarget() == DifficultyTarget.MAX_TESTNET_TARGET.getCompressedTarget())
                  link = linkStorage.getLink(link.getBlock().getPreviousBlockHash());
               if (link != null)
                  return new DifficultyTarget(link.getBlock().getCompressedTarget());
               else
                return DifficultyTarget.MAX_TESTNET_TARGET;
            }
         }
         logger.debug("previous height {}, not change in target",link.getHeight());
         return new DifficultyTarget(link.getBlock().getCompressedTarget());
      }
      // We have to change the target. First collect the last TARGET_RECALC 
      // blocks (including the given block) 
      Block startBlock = link.getBlock();
      for ( int i=0; (i<TARGET_RECALC-1) && (startBlock!=null); i++ )
         startBlock = getPreviousBlock(startBlock);
      // This shouldn't happen, we reached genesis
      if ( startBlock == null )
         return isTestnet ? DifficultyTarget.MAX_TESTNET_TARGET : DifficultyTarget.MAX_PRODNET_TARGET;
      // Calculate the time the TARGET_RECALC blocks took
      long calculatedTimespan = link.getBlock().getCreationTime() - startBlock.getCreationTime();
      if (calculatedTimespan < TARGET_TIMESPAN/4)
         calculatedTimespan = TARGET_TIMESPAN/4;
      if (calculatedTimespan > TARGET_TIMESPAN*4)
         calculatedTimespan = TARGET_TIMESPAN*4;
      // Calculate new target, but allow no more than maximum target
      DifficultyTarget difficultyTarget = new DifficultyTarget(link.getBlock().getCompressedTarget());
      BigInteger target = difficultyTarget.getTarget();
      target = target.multiply(BigInteger.valueOf(calculatedTimespan));
      target = target.divide(BigInteger.valueOf(TARGET_TIMESPAN));
      // Return the new difficulty setting
      DifficultyTarget resultTarget = new DifficultyTarget(target);
      DifficultyTarget maxTarget = isTestnet ? DifficultyTarget.MAX_TESTNET_TARGET : DifficultyTarget.MAX_PRODNET_TARGET;
      if ( resultTarget.compareTo(maxTarget) > 0 )
         return maxTarget;
      else
         resultTarget = new DifficultyTarget(resultTarget.getCompressedTarget()); // Normalize
      logger.debug("previous height {}, recalculated target is: {}",link.getHeight(),resultTarget);
      return resultTarget;
   }

   /**
    * Return a block locator to be used by getBlocks using the specs defined by the wiki:
    * https://en.bitcoin.it/wiki/Protocol_specification#getblocks
    * @return a list of block hashes
    */
   @Override
   public List<byte[]> buildBlockLocator()
   {
      List<byte[]> blocks = new LinkedList<>();
      long topHeight = linkStorage.getLastLink().getHeight();
      int start = 0;
      int step = 1;
      for (long i = topHeight; i > 0; i -= step, ++start)
      {
         if (start >= 10)
            step *= 2;
         blocks.add(linkStorage.getHashOfMainChainAtHeight(i));
      }
      blocks.add(getGenesisBlock().getHash());
      return blocks;
   }

   @Override
   public Block getGenesisBlock()
   {
      return genesisBlock;
   }

   @Override
   public Block getLastBlock()
   {
      return linkStorage.getLastLink().getBlock();
   }

   @Override
   public long getHeight()
   {
      return linkStorage.getHeight();
   }

   static
   {
      logger.debug("reading known hashes...");
      try
      {
         Map<Integer,BigInteger> idGenesis = new HashMap<>();
         ResourceBundle bundle = ResourceBundle.getBundle("chain-knownhashes");
         // We need to sort the keys of the bundle
         for (String key : new TreeSet<>(bundle.keySet()))
         {
            StringTokenizer tokens = new StringTokenizer(key,".");
            tokens.nextToken();
            int id = Integer.valueOf(tokens.nextToken());
            long height = Long.valueOf(tokens.nextToken());
            BigInteger hash = new BigInteger(bundle.getString(key).substring(2),16);
            // Put into maps
            if ( height == 0 )
            {
               // This is a genesis block, so rememeber and create maps
               idGenesis.put(id,hash);
               knownHashes.put(hash,new HashMap<Long,BigInteger>());
            }
            else
            {
               // This is a random height, so there should be a map for that
               Map<Long, BigInteger> values = knownHashes.get(idGenesis.get(id));
               if ( values == null )
                  logger.warn("can not accept known value for id "+id+", height "+height+
                        ", because genesis hash not yet defined");
               else
                  values.put(height,hash);
            }
         }
      } catch ( MissingResourceException e ) {
         logger.warn("can not read known hashes for the block chain, security might be impacted",e);
      }
   }

}

