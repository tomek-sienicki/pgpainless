/*
 * Copyright 2018 Paul Schaub.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pgpainless.key.generation;


import javax.annotation.Nonnull;
import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector;
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.bouncycastle.openpgp.operator.PGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.pgpainless.algorithm.HashAlgorithm;
import org.pgpainless.algorithm.KeyFlag;
import org.pgpainless.key.collection.PGPKeyRing;
import org.pgpainless.key.generation.type.ECDH;
import org.pgpainless.key.generation.type.ECDSA;
import org.pgpainless.key.generation.type.KeyType;
import org.pgpainless.key.generation.type.RSA_GENERAL;
import org.pgpainless.key.generation.type.curve.EllipticCurve;
import org.pgpainless.key.generation.type.length.RsaLength;
import org.pgpainless.provider.ProviderFactory;
import org.pgpainless.util.Passphrase;

public class KeyRingBuilder implements KeyRingBuilderInterface {

    private final Charset UTF8 = Charset.forName("UTF-8");

    private List<KeySpec> keySpecs = new ArrayList<>();
    private String userId;
    private Passphrase passphrase;

    /**
     * Creates a simple RSA KeyPair of length {@code length} with user-id {@code userId}.
     * The KeyPair consists of a single RSA master key which is used for signing, encryption and certification.
     *
     * @param userId user id.
     * @param length length in bits.
     * @return {@link PGPSecretKeyRing} containing the KeyPair.
     *
     * @throws PGPException
     * @throws NoSuchAlgorithmException
     * @throws InvalidAlgorithmParameterException
     */
    public PGPKeyRing simpleRsaKeyRing(@Nonnull String userId, @Nonnull RsaLength length)
            throws PGPException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        return withMasterKey(
                        KeySpec.getBuilder(RSA_GENERAL.withLength(length))
                                .withDefaultKeyFlags()
                                .withDefaultAlgorithms())
                .withPrimaryUserId(userId)
                .withoutPassphrase()
                .build();
    }

    /**
     * Creates a key ring consisting of an ECDSA master key and an ECDH sub-key.
     * The ECDSA master key is used for signing messages and certifying the sub key.
     * The ECDH sub-key is used for encryption of messages.
     *
     * @param userId user-id
     * @return {@link PGPSecretKeyRing} containing the key pairs.
     *
     * @throws PGPException
     * @throws NoSuchAlgorithmException
     * @throws InvalidAlgorithmParameterException
     */
    public PGPKeyRing simpleEcKeyRing(@Nonnull String userId)
            throws PGPException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        return withSubKey(
                        KeySpec.getBuilder(ECDH.fromCurve(EllipticCurve._P256))
                                .withKeyFlags(KeyFlag.ENCRYPT_STORAGE, KeyFlag.ENCRYPT_COMMS)
                                .withDefaultAlgorithms())
                .withMasterKey(
                        KeySpec.getBuilder(ECDSA.fromCurve(EllipticCurve._P256))
                                .withKeyFlags(KeyFlag.AUTHENTICATION, KeyFlag.CERTIFY_OTHER, KeyFlag.SIGN_DATA)
                                .withDefaultAlgorithms())
                .withPrimaryUserId(userId)
                .withoutPassphrase()
                .build();
    }

    @Override
    public KeyRingBuilderInterface withSubKey(@Nonnull KeySpec type) {
        KeyRingBuilder.this.keySpecs.add(type);
        return this;
    }

    @Override
    public WithPrimaryUserId withMasterKey(@Nonnull KeySpec spec) {
        if (canCertifyOthers(spec)) {
            throw new IllegalArgumentException("Certification Key MUST have KeyFlag CERTIFY_OTHER");
        }

        KeyRingBuilder.this.keySpecs.add(0, spec);
        return new WithPrimaryUserIdImpl();
    }

    private boolean canCertifyOthers(KeySpec keySpec) {
        return KeyFlag.hasKeyFlag(keySpec.getSubpackets().getKeyFlags(), KeyFlag.CERTIFY_OTHER);
    }

    class WithPrimaryUserIdImpl implements WithPrimaryUserId {

        @Override
        public WithPassphrase withPrimaryUserId(@Nonnull String userId) {
            KeyRingBuilder.this.userId = userId;
            return new WithPassphraseImpl();
        }

        @Override
        public WithPassphrase withPrimaryUserId(@Nonnull byte[] userId) {
            return withPrimaryUserId(new String(userId, UTF8));
        }
    }

    class WithPassphraseImpl implements WithPassphrase {

        @Override
        public Build withPassphrase(@Nonnull Passphrase passphrase) {
            KeyRingBuilder.this.passphrase = passphrase;
            return new BuildImpl();
        }

        @Override
        public Build withoutPassphrase() {
            KeyRingBuilder.this.passphrase = null;
            return new BuildImpl();
        }

        class BuildImpl implements Build {

            private PGPDigestCalculator digestCalculator;
            private PBESecretKeyEncryptor secretKeyEncryptor;

            @Override
            public PGPKeyRing build() throws NoSuchAlgorithmException, PGPException,
                    InvalidAlgorithmParameterException {
                digestCalculator = buildDigestCalculator();
                secretKeyEncryptor = buildSecretKeyEncryptor();

                // First key is the Master Key
                KeySpec certKeySpec = keySpecs.remove(0);

                // Generate Master Key
                PGPKeyPair certKey = generateKeyPair(certKeySpec);
                PGPContentSignerBuilder signer = buildContentSigner(certKey);
                PGPSignatureSubpacketVector hashedSubPackets = certKeySpec.getSubpackets();

                // Generator which the user can get the key pair from
                PGPKeyRingGenerator ringGenerator = buildRingGenerator(certKey, signer, hashedSubPackets);

                addSubKeys(ringGenerator);

                PGPPublicKeyRing publicKeys = ringGenerator.generatePublicKeyRing();
                PGPSecretKeyRing secretKeys = ringGenerator.generateSecretKeyRing();

                return new PGPKeyRing(publicKeys, secretKeys);
            }

            private PGPKeyRingGenerator buildRingGenerator(PGPKeyPair certKey, PGPContentSignerBuilder signer, PGPSignatureSubpacketVector hashedSubPackets) throws PGPException {
                return new PGPKeyRingGenerator(
                                    PGPSignature.POSITIVE_CERTIFICATION, certKey,
                                    userId, digestCalculator,
                                    hashedSubPackets, null, signer, secretKeyEncryptor);
            }

            private void addSubKeys(PGPKeyRingGenerator ringGenerator) throws NoSuchAlgorithmException, PGPException, InvalidAlgorithmParameterException {
                for (KeySpec subKeySpec : keySpecs) {
                    PGPKeyPair subKey = generateKeyPair(subKeySpec);
                    if (subKeySpec.isInheritedSubPackets()) {
                        ringGenerator.addSubKey(subKey);
                    } else {
                        ringGenerator.addSubKey(subKey, subKeySpec.getSubpackets(), null);
                    }
                }
            }

            private PGPContentSignerBuilder buildContentSigner(PGPKeyPair certKey) {
                return new JcaPGPContentSignerBuilder(
                                    certKey.getPublicKey().getAlgorithm(), HashAlgorithm.SHA512.getAlgorithmId())
                                    .setProvider(ProviderFactory.getProvider());
            }

            private PBESecretKeyEncryptor buildSecretKeyEncryptor() {
                PBESecretKeyEncryptor encryptor = passphrase == null ?
                        null : // unencrypted key pair, otherwise AES-256 encrypted
                        new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, digestCalculator)
                                .setProvider(ProviderFactory.getProvider())
                                .build(passphrase.getChars());
                if (passphrase != null) {
                    passphrase.clear();
                }
                return encryptor;
            }

            private PGPDigestCalculator buildDigestCalculator() throws PGPException {
                return new JcaPGPDigestCalculatorProviderBuilder()
                        .setProvider(ProviderFactory.getProvider())
                        .build()
                        .get(HashAlgorithm.SHA1.getAlgorithmId());
            }

            private PGPKeyPair generateKeyPair(KeySpec spec)
                    throws NoSuchAlgorithmException, PGPException,
                    InvalidAlgorithmParameterException {
                KeyType type = spec.getKeyType();
                KeyPairGenerator certKeyGenerator = KeyPairGenerator.getInstance(type.getName(), ProviderFactory.getProvider());
                certKeyGenerator.initialize(type.getAlgorithmSpec());

                // Create raw Key Pair
                KeyPair keyPair = certKeyGenerator.generateKeyPair();

                // Form PGP key pair
                PGPKeyPair pgpKeyPair = new JcaPGPKeyPair(type.getAlgorithm().getAlgorithmId(),
                        keyPair, new Date());

                return pgpKeyPair;
            }
        }
    }
}
