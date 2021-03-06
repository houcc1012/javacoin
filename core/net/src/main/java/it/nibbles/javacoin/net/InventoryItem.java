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

package it.nibbles.javacoin.net;

import it.nibbles.javacoin.utils.BtcUtil;
import java.io.IOException;

/**
 * @author Robert Brautigam
 */
public class InventoryItem
{
   public static final int TYPE_ERROR = 0;
   public static final int TYPE_TX = 1;
   public static final int TYPE_BLOCK = 2;

   private int type = TYPE_ERROR;
   private byte[] hash;

   public InventoryItem(int type, byte[] hash)
   {
      this.type=type;
      this.hash=hash;
   }

   InventoryItem()
   {
   }

   void readFrom(BitcoinInputStream input)
      throws IOException
   {
      type = (int) input.readUInt32();
      hash = input.readReverseBytes(32);
   }

   void writeTo(BitcoinOutputStream output)
      throws IOException
   {
      output.writeUInt32(type);
      output.writeReverse(hash);
   }

   public String toString()
   {
      return type+": "+BtcUtil.hexOut(hash);
   }

   public int getType()
   {
      return type;
   }

   public byte[] getHash()
   {
      return hash;
   }
}

