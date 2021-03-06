/**
 * Copyright 2011 John Sample
 *
 * Shameless copied and adapted from bitcoinj
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 * 
 * Adapted for JavaCoin by Alessandro Polverini
 */
package it.nibbles.javacoin.net.p2p.source;

import it.nibbles.javacoin.BitcoinException;
import it.nibbles.javacoin.net.p2p.AddressSource;
import it.nibbles.javacoin.utils.Base58;
import it.nibbles.javacoin.utils.BtcUtil;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IrcAddressSource provides a way to find network peers by joining a pre-agreed
 * rendevouz point on the LFnet IRC network.
 */
public class IrcAddressSource implements AddressSource
{

   private static final Logger logger = LoggerFactory.getLogger(IrcAddressSource.class);
   private String channel;
   private int port = 6667;
   private String server;
   private BufferedWriter writer = null;
   private Socket connection;

   /**
    * Finds a list of peers by connecting to an IRC network, joining a channel,
    * decoding the nicks and then disconnecting.
    *
    * @param channel The IRC channel to join, either "#bitcoin" or
    * "#bitcoinTEST" for the production and test networks respectively.
    */
   public IrcAddressSource(String channel)
   {
      this(channel, "irc.lfnet.org", 6667);
   }

   /**
    * Finds a list of peers by connecting to an IRC network, joining a channel,
    * decoding the nicks and then disconnecting.
    *
    * @param server Name or textual IP address of the IRC server to join.
    * @param channel The IRC channel to join, either "#bitcoin" or
    * "#bitcoinTEST" for the production and test networks
    */
   public IrcAddressSource(String channel, String server, int port)
   {
      this.channel = channel;
      this.server = server;
      this.port = port;
   }

   protected void onIRCSend(String message)
   {
   }

   protected void onIRCReceive(String message)
   {
   }

   public void shutdown()
   {
      try
      {
         if (connection != null)
         {
            connection.close();
         }
      } catch (IOException ex)
      {
         // ignore
      }
   }

   /**
    * Returns a list of peers that were found in the IRC channel. Note that just
    * because a peer appears in the list does not mean it is accepting
    * connections.
    */
   @Override
   public List<InetSocketAddress> getAddresses()
   {
      List<InetSocketAddress> addresses = new ArrayList<>();
      connection = null;
      try
      {
         logger.info("Connecting to IRC with " + InetAddress.getByName(server));
         connection = new Socket(server, port);
         writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), "UTF-8"));
         BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));

         // Generate a random nick for the connection. This is chosen to be clearly identifiable as coming from
         // JavaCoin but not match the standard nick format, so full peers don't try and connect to us.
         String nickRnd = String.format("jvc%d", new Random().nextInt(Integer.MAX_VALUE));
         String command = "NICK " + nickRnd;
         logAndSend(command);
         // USER <user> <mode> <unused> <realname> (RFC 2812)
         command = "USER " + nickRnd + " 8 *: " + nickRnd;
         logAndSend(command);
         writer.flush();

         // Wait to be logged in. Worst case we end up blocked until the server PING/PONGs us out.
         String currLine;
         while ((currLine = reader.readLine()) != null)
         {
            onIRCReceive(currLine);
            // 004 tells us we are connected
            // TODO: add common exception conditions (nick already in use, etc..)
            // these aren't bullet proof checks but they should do for our purposes.
            if (checkLineStatus("004", currLine))
            {
               break;
            }
         }

         // Join the channel.
         logAndSend("JOIN " + channel);
         // List users in channel.
         logAndSend("NAMES " + channel);
         writer.flush();

         // A list of the users should be returned. Look for code 353 and parse until code 366.
         while ((currLine = reader.readLine()) != null)
         {
            onIRCReceive(currLine);
            if (checkLineStatus("353", currLine))
            {
               // Line contains users. List follows ":" (second ":" if line starts with ":")
               int subIndex = 0;
               if (currLine.startsWith(":"))
               {
                  subIndex = 1;
               }

               String spacedList = currLine.substring(currLine.indexOf(":", subIndex));
               addresses.addAll(parseUserList(spacedList.substring(1).split(" ")));
            } else if (checkLineStatus("366", currLine))
            {
               // End of user list.
               break;
            }
         }

         // Quit the server.
         logAndSend("PART " + channel);
         logAndSend("QUIT");
         writer.flush();
      } catch (Exception e)
      {
         logger.info(e.getMessage(), e);
      } finally
      {
         try
         {
            // No matter what try to close the connection.
            connection.close();
         } catch (Exception e2)
         {
         }
      }
      return addresses;
   }

   private void logAndSend(String command) throws Exception
   {
      onIRCSend(command);
      writer.write(command + "\n");
   }

   // Visible for testing.
   static ArrayList<InetSocketAddress> parseUserList(String[] userNames) throws UnknownHostException
   {
      ArrayList<InetSocketAddress> addresses = new ArrayList<>();
      for (String user : userNames)
      {
         // All Bitcoin peers start their nicknames with a 'u' character.
         if (!user.startsWith("u"))
         {
            continue;
         }

         // After "u" is stripped from the beginning array contains unsigned chars of:
         // 4 byte ip address, 2 byte port, 4 byte hash check (ipv4)

         byte[] addressBytes;
         try
         {
            // Strip off the "u" before decoding. Note that it's possible for anyone to join these IRC channels and
            // so simply beginning with "u" does not imply this is a valid Bitcoin encoded address.
            //
            // decodeChecked removes the checksum from the returned bytes.
            addressBytes = Base58.decodeChecked(user.substring(1));
         } catch (BitcoinException e)
         {
            logger.warn("IRC nick does not parse as base58: " + user+" error: "+e.getMessage());
            continue;
         }

         // TODO: Handle IPv6 if one day the official client uses it. It may be that IRC discovery never does.
         if (addressBytes.length != 6)
         {
            continue;
         }

         byte[] ipBytes = new byte[]
         {
            addressBytes[0], addressBytes[1], addressBytes[2], addressBytes[3]
         };
         int port = BtcUtil.readUint16BE(addressBytes, 4);

         InetAddress ip;
         try
         {
            ip = InetAddress.getByAddress(ipBytes);
         } catch (UnknownHostException e)
         {
            // Bytes are not a valid IP address.
            continue;
         }

         InetSocketAddress address = new InetSocketAddress(ip, port);
         addresses.add(address);
      }

      return addresses;
   }

   private static boolean checkLineStatus(String statusCode, String response)
   {
      // Lines can either start with the status code or an optional :<source>
      //
      // All the testing shows the servers for this purpose use :<source> but plan for either.
      // TODO: Consider whether regex would be worth it here.
      if (response.startsWith(":"))
      {
         // Look for first space.
         int startIndex = response.indexOf(" ") + 1;
         // Next part should be status code.
         if (response.indexOf(statusCode + " ", startIndex) == startIndex)
         {
            return true;
         } else
         {
            return false;
         }
      } else
      {
         if (response.startsWith(statusCode + " "))
         {
            return true;
         }
      }
      return false;
   }
}
