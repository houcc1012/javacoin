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

package hu.netmind.bitcoin.net.message;

import hu.netmind.bitcoin.net.GetBlocks;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

/**
 * @author Robert Brautigam
 */
public class GetBlocksImpl extends ChecksummedMessageImpl implements GetBlocks
{
   private List<byte[]> hashStarts;
   private byte[] hashStop;

   public GetBlocksImpl(long magic, List<byte[]> hashStarts, byte[] hashStop)
      throws IOException
   {
      super(magic,"getblocks");
      this.hashStarts=hashStarts;
      this.hashStop=hashStop;
   }

   GetBlocksImpl()
      throws IOException
   {
      super();
   }

   void readFrom(BitCoinInputStream input, long version, Object param)
      throws IOException
   {
      super.readFrom(input,version,param);
      long size = input.readUIntVar();
      hashStarts = new ArrayList<byte[]>();
      for ( long i=0; i<size; i++ )
         hashStarts.add(input.readBytes(32));
      hashStop = input.readBytes(32);
   }

   void writeTo(BitCoinOutputStream output, long version)
      throws IOException
   {
      super.writeTo(output,version);
      output.writeUIntVar(hashStarts.size());
      for ( byte[] hash : hashStarts )
         output.write(hash);
      output.write(hashStop);
   }

   public String toString()
   {
      return super.toString()+" hash starts: "+hashStarts+", stop: "+hashStop;
   }

   public List<byte[]> getHashStarts()
   {
      return hashStarts;
   }

   public byte[] getHashStop()
   {
      return hashStop;
   }
}
