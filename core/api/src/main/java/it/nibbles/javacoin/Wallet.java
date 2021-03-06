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

package it.nibbles.javacoin;

import java.util.List;

/**
 * A Wallet is a high-level construct which contains Coins representing
 * the total value the owner has. This class does not reflect the tree
 * of block chains, rather it must follow the changes in the block chain
 * and represent a single consistent view of coins on all calls. This means
 * that the number and value of coins can change without warning, although
 * after the change the COIN_CHANGE event is always sent.
 * @author Robert Brautigam
 */
public interface Wallet extends Observable
{
   enum Event
   {
      COIN_CHANGE
   };

   /**
    * Get all the coins in this wallet. It is guaranteed
    * that the returned list is always a consistent view.
    */
   List<Coin> getCoins();

   /**
    * Mark the listed coins as spent. This is one atomic
    * operation, so if it succeeds then all coins are marked
    * as spent.
    * If the coins are not spent according to the block chain
    * in a given amount of time, the coins will be automatically
    * reclaimed.
    * @throws InvalidCoinException If one or more coins were not
    * acually part of this wallet anymore, or one or more coins
    * were already spent.
    */
   void spend(Coin... coins)
      throws InvalidCoinException;

   /**
    * Reclaim coins. This is the inverse operation to <code>spend()</code>.
    * The coins given will be reclaimed in one atomic operation. 
    * Note that if you reclaim Coins that were actually spent successfully,
    * you get coins that can't be actually spent anymore. Use this method
    * explicitly only if it is fairly certain that the spending operation
    * did not succeed.
    * @throws InvalidCoinException If one or more coins were not
    * acually part of this wallet anymore.
    */
   void reclaim(Coin... coins)
      throws InvalidCoinException;
}

