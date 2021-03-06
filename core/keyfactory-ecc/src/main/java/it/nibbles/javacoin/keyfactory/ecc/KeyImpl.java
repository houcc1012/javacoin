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

package it.nibbles.javacoin.keyfactory.ecc;

import it.nibbles.javacoin.Key;
import it.nibbles.javacoin.PublicKey;
import it.nibbles.javacoin.VerificationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequenceGenerator;
import org.bouncycastle.asn1.DLSequence;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The key implementation using ECC.
 * @author Robert Brautigam
 */
public class KeyImpl implements Key
{
   private static final Logger logger = LoggerFactory.getLogger(KeyImpl.class);

   private static SecureRandom random;
   private static ECDomainParameters domainParameters;

   private long creationTime;
   private BigInteger privateKey;
   private byte[] publicKey;

   private PublicKeyImpl publicKeyObject;

   /**
    * Create a key. With this constructor a new key will be
    * actually generated.
    */
   public KeyImpl()
   {
      // Initialize generator
      ECKeyPairGenerator generator = new ECKeyPairGenerator();
      generator.init(new ECKeyGenerationParameters(domainParameters,random));
      // Generate key
      AsymmetricCipherKeyPair keyPair = generator.generateKeyPair();
      // Get values
      privateKey = ((ECPrivateKeyParameters)keyPair.getPrivate()).getD();
      publicKey = ((ECPublicKeyParameters)keyPair.getPublic()).getQ().getEncoded(); // This encodes whole point actually
      // Create public key object
      publicKeyObject = new PublicKeyImpl(publicKey);
      // Remember creation time
      creationTime = System.currentTimeMillis();
   }

   /**
    * Create the key with all attributes supplied suitable for reconstructing an object
    * from storage. With this constructor, nothing will be calculated.
    */
   public KeyImpl(BigInteger privateKey, byte[] publicKey, byte[] pubHash, long creationTime)
   {
      this.privateKey=privateKey;
      this.publicKey=publicKey;
      this.creationTime=creationTime;
      this.publicKeyObject = new PublicKeyImpl(publicKey,pubHash); // No need to generate hash
   }
   
   @Override
   public long getCreationTime()
   {
      return creationTime;
   }
   public BigInteger getPrivateKey()
   {
      return privateKey;
   }

   /**
    * Sign a block of data with this private key.
    * @param data The data to sign.
    * @return The signature of the data compatible with Bitcoin specification.
    */
   @Override
   public byte[] sign(byte[] data)
      throws VerificationException
   {
      // Bitcoin wiki specifies signature to be produced by EC-DSA, so init
      ECDSASigner signer = new ECDSASigner();
      signer.init(true,new ECPrivateKeyParameters(privateKey,domainParameters));
      // Sign
      BigInteger[] signature = signer.generateSignature(data);
      // As per specification signatures must be DER encoded, and both components
      // must be concatenated. Luckily bouncycastle provides that also.
      try
      {
         ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
         DERSequenceGenerator derOutput = new DERSequenceGenerator(byteOutput);
         derOutput.addObject(new ASN1Integer(signature[0]));
         derOutput.addObject(new ASN1Integer(signature[1]));
         derOutput.close();
         return byteOutput.toByteArray();
      } catch (IOException e) {
         throw new VerificationException("could not encode signature to DER stream",e);
      }
   }

   /**
    * Get the public key of this private key.
    */
   @Override
   public PublicKey getPublicKey()
   {
      return publicKeyObject;
   }

   /**
    * Implementation of the public key interface is included so that all encoding related
    * logic is in one place. It also re-uses the initialized domain parameters of the curve, but
    * it also can be used independently of instances of key.
    */
   public static class PublicKeyImpl implements PublicKey
   {
      private byte[] publicKey;
      private byte[] hash;

      /**
       * Create the public key object with only the public key byte
       * representation. In this case the hash will be calculated.
       */
      PublicKeyImpl(byte[] publicKey)
      {
         this.publicKey=publicKey;
         // Calculate hash RIPEMD-160(SHA-256(public key))
         try
         {
            MessageDigest shaDigest = MessageDigest.getInstance("SHA-256");
            byte[] shaResult = shaDigest.digest(publicKey);
            RIPEMD160Digest ripeDigest = new RIPEMD160Digest();
            ripeDigest.update(shaResult,0,shaResult.length);
            hash = new byte[ripeDigest.getDigestSize()]; // Should be actually 20 bytes (160 bits)
            ripeDigest.doFinal(hash,0);
         } catch ( NoSuchAlgorithmException e ) {
            throw new RuntimeException("could not get SHA-256 algorithm, crypto will not work at all",e);
         }
      }

      /**
       * Create with both key and hash already given.
       */
      PublicKeyImpl(byte[] publicKey, byte[] hash)
      {
         this.publicKey=publicKey;
         this.hash=hash;
      }

      /**
       * Verify that a block of data was signed with the private counterpart
       * of this public key.
       * @param data The data that was supposed to be signed.
       * @param signature The signature for that data.
       * @return True if the signature is a correct signature of the given data
       * for the private key counterpart of this public key, false otherwise.
       */
      @Override
      public boolean verify(byte[] data, byte[] signature)
         throws VerificationException
      {
         try
         {
            // First, get back the "r" and "s" values from the concatenated DER signature
            ASN1InputStream derInput = new ASN1InputStream(signature);
            DLSequence sequence = (DLSequence) derInput.readObject();
            BigInteger r = ((ASN1Integer)sequence.getObjectAt(0)).getPositiveValue();
            BigInteger s = ((ASN1Integer)sequence.getObjectAt(1)).getPositiveValue();
            derInput.close();
            // Now verify
            ECDSASigner signer = new ECDSASigner();
            signer.init(false,new ECPublicKeyParameters(
                     domainParameters.getCurve().decodePoint(publicKey),domainParameters));
            return signer.verifySignature(data,r,s);
         } catch ( IOException e ) {
            throw new VerificationException("could not read signature values from signature bytes",e);
         }
      }

      public byte[] getPublicKey()
      {
         return publicKey;
      }
      @Override
      public byte[] getHash()
      {
         return hash;
      }
   }
         
   static
   {
      try
      {
         random = new SecureRandom();
         // Create domain parameters out of the ec parameters
         X9ECParameters params = SECNamedCurves.getByName("secp256k1");
         logger.debug("x9ec parameters: "+params);
         domainParameters = new ECDomainParameters(params.getCurve(), params.getG(), 
               params.getN(),  params.getH(), params.getSeed());
      } catch ( Exception e ) {
         logger.error("error initializing ecc encryption, crypto will not work",e);
      }
   }

}

