/**
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
package it.nibbles.bitcoin;

import com.fasterxml.jackson.databind.ObjectMapper;
import hu.netmind.bitcoin.BitcoinException;
import hu.netmind.bitcoin.Block;
import hu.netmind.bitcoin.BlockChain;
import hu.netmind.bitcoin.Transaction;
import hu.netmind.bitcoin.TransactionInput;
import hu.netmind.bitcoin.TransactionOutput;
import hu.netmind.bitcoin.block.BitcoinFactory;
import hu.netmind.bitcoin.block.BlockChainImpl;
import hu.netmind.bitcoin.block.BlockChainLink;
import hu.netmind.bitcoin.block.BlockChainLinkStorage;
import hu.netmind.bitcoin.block.BlockImpl;
import hu.netmind.bitcoin.block.ProdnetBitcoinFactory;
import hu.netmind.bitcoin.block.Testnet2BitcoinFactory;
import hu.netmind.bitcoin.block.Testnet3BitcoinFactory;
import hu.netmind.bitcoin.block.TransactionImpl;
import hu.netmind.bitcoin.block.TransactionInputImpl;
import hu.netmind.bitcoin.block.TransactionOutputImpl;
import hu.netmind.bitcoin.block.jdbc.DatasourceUtils;
import hu.netmind.bitcoin.block.jdbc.MysqlStorage;
import hu.netmind.bitcoin.keyfactory.ecc.KeyFactoryImpl;
import hu.netmind.bitcoin.script.ScriptFactoryImpl;
import it.nibbles.bitcoin.utils.BtcUtil;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

/**
 * Simple class to work on blockchain blocks, for testing purposes
 *
 * @author Alessandro Polverini
 */
public class BlockTool {

  //private static Logger logger = LoggerFactory.getLogger(BlockTool.class);
  private static final long BC_PROTOCOL_VERSION = 32100;
  private static final String STORAGE_BDB = "bdb";
  private static final String STORAGE_JDBC = "jdbc";
  private static final String STORAGE_MEMORY = "memory";
  private static BlockChainLinkStorage storage;
  private static ScriptFactoryImpl scriptFactory;
  private static BitcoinFactory bitcoinFactory;
  private static final String HELP_TEXT =
          "BlockTool: Dump local BlockChain blocks for testint purposes\n\n"
          + "Usage:\n"
          + "  commands:\n"
          + "  --load               Read blocks from inputfile and store them to storage.\n"
          + "  --save               Read blocks from storage and write them to the specified outputfile.\n"
          + "  general options:\n"
          + "  --hash=n             Hash of block to operate.\n"
          + "  --first=n            First block to operate.\n"
          + "  --last=n             Last block to operate.\n"
          + "  --testnet            Connect to Bitcoin test network.\n"
          + "  --prodnet            Connect to Bitcoin production network (default).\n"
          + "  --port=<port>        Listen for incoming connection on the provided port instead of the default.\n"
          + "  --inputfile=<file>   Name of the file used to read from.\n"
          + "  --outputfile=<file>  Name of the file used to write to.\n"
          + "  storage options:\n"
          + "  --storage=<engine>   Available storage engines: bdb, jdbc, memory.\n"
          + "  --bdb-path=path      Path to BDB storage files.\n"
          + "  --url=<url>          Specifies JDBC url.\n"
          + "  --driver=<class>     Specifies the class name of the JDBC driver. Defaults to 'com.mysql.jdbc.Driver'\n"
          + "  --dbuser=<username>  Specify database username. Defaults to 'bitcoinj'\n"
          + "  --dbpass=<password>  Specify database password to use for the connection.\n";
  private static int firstBlock, lastBlock;
  private static String blockHash;
  private static String storageType = STORAGE_JDBC;
  private static boolean isProdnet = false;
  private static boolean isTestNet2 = false;
  private static boolean isTestNet3 = false;
  private static OptionSpec<String> optBdbPath;
  private static OptionSpec<String> optJdbcUrl;
  private static OptionSpec<String> optJdbcUser;
  private static OptionSpec<String> optJdbcPassword;
  private static OptionSpec<String> inputfile;
  private static OptionSpec<String> outputfile;
  private static OptionSpec<String> revalidateOption;
  private static boolean cmdSaveBlockchain = false;
  private static boolean cmdLoadBlockchain = false;

  public static void main(String[] args) throws Exception {
    OptionParser parser = new OptionParser();
    parser.accepts("help");
    parser.accepts("load");
    parser.accepts("save");
    parser.accepts("testnet2");
    parser.accepts("testnet3");
    parser.accepts("prodnet");
    parser.accepts("first").withRequiredArg().ofType(Integer.class);
    parser.accepts("last").withRequiredArg().ofType(Integer.class);
    parser.accepts("hash").withRequiredArg();
    parser.accepts("port").withRequiredArg().ofType(Integer.class);
    optBdbPath = parser.accepts("bdbPath").withRequiredArg().defaultsTo("data");
    //optJdbcDriver = parser.accepts("driver").withRequiredArg().defaultsTo("com.mysql.jdbc.Driver");
    optJdbcUrl = parser.accepts("url").withRequiredArg().defaultsTo("jdbc:mysql://localhost/javacoin_testnet3");
    optJdbcUser = parser.accepts("dbuser").withRequiredArg().defaultsTo("javacoin");
    optJdbcPassword = parser.accepts("dbpass").withRequiredArg().defaultsTo("pw");
    inputfile = parser.accepts("inputfile").withRequiredArg();
    outputfile = parser.accepts("outputfile").withRequiredArg();
    OptionSet options = parser.parse(args);
    if (args.length == 0
            || options.hasArgument("help")
            || options.nonOptionArguments().size() > 0
            || (options.has("save") && options.has("load"))
            || (options.has("save") && !options.has("outputfile"))
            || (options.has("load") && !options.has("inputfile"))
            || (options.has("testnet2") && options.has("testnet3"))
            || (options.has("testnet2") && options.has("prodnet"))
            || (options.has("testnet3") && options.has("prodnet"))) {
      println(HELP_TEXT);
      return;
    }
    if (options.hasArgument("port")) {
      //listenPort = ((Integer) options.valueOf("port")).intValue();
    }
    cmdSaveBlockchain = options.has("save");
    cmdLoadBlockchain = options.has("load");
    isProdnet = options.has("prodnet");
    isTestNet2 = options.has("testnet2");
    isTestNet3 = options.has("testnet3");
    if (options.hasArgument("first")) {
      firstBlock = ((Integer) options.valueOf("first")).intValue();
      if (!options.hasArgument("last"))
        lastBlock = firstBlock;
    }
    if (options.hasArgument("last")) {
      lastBlock = ((Integer) options.valueOf("last")).intValue();
      if (!options.hasArgument("first"))
        firstBlock = lastBlock;
    }
    if (options.hasArgument("hash"))
      blockHash = (String) options.valueOf("hash");
    if (cmdSaveBlockchain && blockHash == null && firstBlock == 0 && lastBlock == 0) {
      println("To save blocks you have to specify a range or an hash");
      return;
    }

    //println("save: " + cmdSaveBlockchain + " load: " + cmdLoadBlockchain + " prodnet: " + isProdnet + " testnet2: " + isTestNet2 + " testnet3: " + isTestNet3);
    //println("FirstBlock: " + firstBlock + " lastBlock: " + lastBlock + " inputfile: " + inputfile.value(options) + " outputfile: " + outputfile.value(options));
    BlockTool app = new BlockTool();

    app.init(options);
    if (cmdLoadBlockchain) {
      BufferedReader reader;
      if ("-".equals(inputfile.value(options)))
        reader = new BufferedReader(new InputStreamReader(System.in));
      else
        reader = new BufferedReader(new FileReader(inputfile.value(options)));
      int numBlocks = 0;
      Block block = app.readBlock(reader);
      while (block != null) {
        numBlocks++;
        block = app.readBlock(reader);
      }
      System.out.println("Numero blocchi letti: " + numBlocks);
    } else if (cmdSaveBlockchain) {
      BlockChainLink blockLink;
      try (PrintWriter writer = new PrintWriter(new File(outputfile.value(options)))) {
        if (blockHash != null) {
          blockLink = storage.getLink(BtcUtil.hexIn(blockHash));
          app.writeBlock(writer, blockLink.getBlock());
        } else {
          for (int i = firstBlock; i <= lastBlock; i++) {
            blockLink = storage.getLinkAtHeight(i);
            app.writeBlock(writer, blockLink.getBlock());
          }
        }
      }
    }
    app.close();
  }

  /**
   * Initialize and bind components together.
   */
  public void init(OptionSet options) throws BitcoinException, ClassNotFoundException {
    scriptFactory = new ScriptFactoryImpl(new KeyFactoryImpl(null));
    bitcoinFactory = isTestNet3 ? new Testnet3BitcoinFactory(scriptFactory)
            : isTestNet2 ? new Testnet2BitcoinFactory(scriptFactory)
            : new ProdnetBitcoinFactory(scriptFactory);
    // Initialize the correct storage engine
    if (STORAGE_BDB.equalsIgnoreCase(storageType)) {
      println("BDB DISABLED");
      System.exit(33);
//         BDBChainLinkStorage engine = new BDBChainLinkStorage(scriptFactory);
//         engine.setDbPath(optBdbPath.value(options));
//         storage = engine;
    } else if (STORAGE_JDBC.equalsIgnoreCase(storageType)) {
      //JdbcChainLinkStorage engine = new JdbcChainLinkStorage(bitcoinFactory);
      MysqlStorage engine = new MysqlStorage(bitcoinFactory);
      engine.setDataSource(DatasourceUtils.getMysqlDatasource(
              optJdbcUrl.value(options), optJdbcUser.value(options), optJdbcPassword.value(options)));
      engine.init();
      storage = engine;
    }
    //chain = new BlockChainImpl(bitcoinFactory, storage, false);
    // Introduce a small check here that we can read back the genesis block correctly
    storage.getGenesisLink().getBlock().validate();
    println("Storage initialized, last link height: " + storage.getLastLink().getHeight());
  }

  /**
   * Free used resources.
   */
  public void close() {
    if (storage != null) {
    }
  }

  public void readJsonBlock(String fileName) throws FileNotFoundException, IOException {
    ObjectMapper mapper = new ObjectMapper();
    Map jsonObj = (Map) mapper.readValue(new File(fileName), Object.class);
    String hash = (String) jsonObj.get("hash");
    ArrayList<Map> txs = (ArrayList<Map>) jsonObj.get("tx");
    for (Map tx : txs) {
      println("Processing tx: " + tx.get("hash"));
      ArrayList<Map> inputs = (ArrayList<Map>) tx.get("inputs");
      ArrayList<Map> outs = (ArrayList<Map>) tx.get("out");
      for (Map input : inputs)
        println(" Input: " + input.get("prev_out"));
      for (Map output : outs) {
        long value = ((Long) output.get("value")).longValue();
        int type = ((Integer) output.get("type")).intValue();
        println(" Out -- addr: " + output.get("addr") + " value: " + value + " type: " + type);
        TransactionOutputImpl tout = new TransactionOutputImpl(value, scriptFactory.createFragment(null));
        println(" Tout: " + tout);
      }
    }
    println("Lettura da " + fileName + " hash: " + hash + " txs: " + txs);
  }

  public Block readBlock(BufferedReader reader) throws IOException, BitcoinException {
    String line = reader.readLine();
    if (line == null) {
      System.out.println("Fine blocchi in input");
      return null;
    }
    if (line.startsWith("block ")) {
      String[] tokens = line.split("\\s");
      byte[] hash = BtcUtil.hexIn(tokens[1]);
      byte[] previousHash = BtcUtil.hexIn(tokens[2]);
      byte[] merkleRoot = BtcUtil.hexIn(tokens[3]);
      long compressedTarget = Long.parseLong(tokens[4]);
      long creationTime = Long.parseLong(tokens[5]);
      long nonce = Long.parseLong(tokens[6]);
      long version = Long.parseLong(tokens[7]);
      int numTransactions = Integer.parseInt(tokens[8]);
      List<TransactionImpl> txs = new ArrayList<>(numTransactions);
      for (int i = 0; i < numTransactions; i++) {
        txs.add(readTransaction(reader));
      }
      Block block = new BlockImpl(txs, creationTime, nonce, compressedTarget, previousHash, merkleRoot, null, version);
      if (!Arrays.equals(hash, block.getHash())) {
        System.out.println("Block hash non corrispondente: " + BtcUtil.hexOut(hash) + " vs " + BtcUtil.hexOut(block.getHash()));
        return null;
      }
      System.out.println("Blocco " + BtcUtil.hexOut(hash) + " nonce: " + nonce + " numTransazioni: " + numTransactions);
      return block;
    } else
      return null;
  }

  public TransactionImpl readTransaction(BufferedReader reader) throws IOException, BitcoinException {
    String line = reader.readLine();
    if (line == null || !line.startsWith(" tx ")) {
      System.out.println("Error: expecting TX and got: " + line);
      return null;
    }
    String[] tokens = line.substring(4).split("\\s");
    byte[] hash = BtcUtil.hexIn(tokens[0]);
    long lockTime = Long.parseLong(tokens[1]);
    long version = Long.parseLong(tokens[2]);
    int numInputs = Integer.parseInt(tokens[3]);
    int numOutputs = Integer.parseInt(tokens[4]);
    List<TransactionInputImpl> inputs = new ArrayList<>(numInputs);
    for (int i = 0; i < numInputs; i++) {
      String l = reader.readLine();
      if (l == null || !l.startsWith("  in ")) {
        System.out.println("Error: expecting TX IN and got: " + l);
        return null;
      }
      String[] tk = l.substring(5).split("\\s");
      byte[] refHash = BtcUtil.hexIn(tk[0]);
      int refIndex = Integer.parseInt(tk[1]);
      long sequence = Long.parseLong(tk[2]);
      byte[] sig = BtcUtil.hexIn(tk[3]);
      inputs.add(new TransactionInputImpl(refHash, refIndex, bitcoinFactory.getScriptFactory().createFragment(sig), sequence));
    }
    List<TransactionOutputImpl> outputs = new ArrayList<>(numOutputs);
    for (int i = 0; i < numOutputs; i++) {
      String l = reader.readLine();
      if (!l.startsWith("   out ")) {
        System.out.println("Error: expecting TX OUT and got: " + l);
        return null;
      }
      String[] tk = l.substring(7).split("\\s");
      long value = Long.parseLong(tk[0]);
      byte[] script = BtcUtil.hexIn(tk[1]);
      outputs.add(new TransactionOutputImpl(value, bitcoinFactory.getScriptFactory().createFragment(script)));
    }
    TransactionImpl tx = new TransactionImpl(inputs, outputs, lockTime, version);
    if (!Arrays.equals(hash, tx.getHash())) {
      System.out.println("TX hash non corrispondente: " + BtcUtil.hexOut(hash) + " vs " + BtcUtil.hexOut(tx.getHash()));
      return null;
    }
    return tx;
  }

  public void writeBlock(PrintWriter writer, Block block) throws IOException {
    writer.println("block " + BtcUtil.hexOut(block.getHash()) + " "
            + BtcUtil.hexOut(block.getPreviousBlockHash()) + " "
            + BtcUtil.hexOut(block.getMerkleRoot()) + " "
            //+ Long.toHexString(block.getCompressedTarget()) + " "
            + block.getCompressedTarget() + " "
            + block.getCreationTime() + " "
            + block.getNonce() + " "
            + block.getVersion() + " "
            + block.getTransactions().size());
    for (Transaction t : block.getTransactions()) {
      writeTransaction(writer, t);
    }
  }

  public void writeTransaction(PrintWriter writer, Transaction t) {
    List<TransactionInput> inputs = t.getInputs();
    List<TransactionOutput> outputs = t.getOutputs();
    writer.println(" tx " + BtcUtil.hexOut(t.getHash()) + " "
            + t.getLockTime() + " " + t.getVersion() + " " + inputs.size() + " " + outputs.size());
    for (TransactionInput input : inputs) {
      writer.println("  in " + BtcUtil.hexOut(input.getClaimedTransactionHash()) + " "
              + input.getClaimedOutputIndex() + " "
              + input.getSequence() + " "
              + BtcUtil.hexOut(input.getSignatureScript().toByteArray()));
    }
    for (TransactionOutput output : outputs) {
      writer.println("   out " + output.getValue() + " "
              + BtcUtil.hexOut(output.getScript().toByteArray()));
    }
  }

  public static void println(String s) {
    System.out.println(s);
  }
}
