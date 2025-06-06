/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.server.security.delegation;

import static com.google.common.collect.MoreCollectors.onlyElement;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.admin.DelegationTokenConfig;
import org.apache.accumulo.core.clientImpl.AuthenticationTokenIdentifier;
import org.apache.accumulo.core.data.InstanceId;
import org.apache.accumulo.server.WithTestNames;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.security.token.Token;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthenticationTokenSecretManagerTest extends WithTestNames {
  private static final Logger log =
      LoggerFactory.getLogger(AuthenticationTokenSecretManagerTest.class);

  // From org.apache.hadoop.security.token.SecretManager
  private static final String DEFAULT_HMAC_ALGORITHM = "HmacSHA1";
  private static final int KEY_LENGTH = 64;
  private static KeyGenerator keyGen;

  private <T> T getOnlyElement(Collection<T> s) {
    return s.stream().collect(onlyElement());
  }

  @BeforeAll
  public static void setupKeyGenerator() throws Exception {
    // From org.apache.hadoop.security.token.SecretManager
    keyGen = KeyGenerator.getInstance(DEFAULT_HMAC_ALGORITHM);
    keyGen.init(KEY_LENGTH);
  }

  private InstanceId instanceId;
  private DelegationTokenConfig cfg;

  @BeforeEach
  public void setup() {
    instanceId = InstanceId.of(UUID.randomUUID());
    cfg = new DelegationTokenConfig();
  }

  @Test
  public void testAddKey() {
    // 1 minute
    long tokenLifetime = MINUTES.toMillis(1);
    AuthenticationTokenSecretManager secretManager =
        new AuthenticationTokenSecretManager(instanceId, tokenLifetime);

    // Add a single key
    AuthenticationKey authKey = new AuthenticationKey(1, 0, tokenLifetime, keyGen.generateKey());
    secretManager.addKey(authKey);

    // Ensure it's in the cache
    Map<Integer,AuthenticationKey> keys = secretManager.getKeys();
    assertNotNull(keys);
    assertEquals(1, keys.size());
    assertEquals(authKey, getOnlyElement(keys.values()));

    // Add the same key
    secretManager.addKey(authKey);

    // Ensure we still have only one key
    keys = secretManager.getKeys();
    assertNotNull(keys);
    assertEquals(1, keys.size());
    assertEquals(authKey, getOnlyElement(keys.values()));
  }

  @Test
  public void testRemoveKey() {
    // 1 minute
    long tokenLifetime = MINUTES.toMillis(1);
    AuthenticationTokenSecretManager secretManager =
        new AuthenticationTokenSecretManager(instanceId, tokenLifetime);

    // Add a single key
    AuthenticationKey authKey = new AuthenticationKey(1, 0, tokenLifetime, keyGen.generateKey());
    secretManager.addKey(authKey);

    // Ensure it's in the cache
    Map<Integer,AuthenticationKey> keys = secretManager.getKeys();
    assertNotNull(keys);
    assertEquals(1, keys.size());
    assertEquals(authKey, getOnlyElement(keys.values()));

    assertTrue(secretManager.removeKey(authKey.getKeyId()));
    assertEquals(0, secretManager.getKeys().size());
  }

  @Test
  public void testGenerateToken() throws Exception {
    // start of the test
    long then = System.currentTimeMillis();

    // 1 minute
    long tokenLifetime = MINUTES.toMillis(1);
    AuthenticationTokenSecretManager secretManager =
        new AuthenticationTokenSecretManager(instanceId, tokenLifetime);

    // Add a current key
    secretManager
        .addKey(new AuthenticationKey(1, then, then + tokenLifetime, keyGen.generateKey()));

    String principal = "user@EXAMPLE.COM";
    Entry<Token<AuthenticationTokenIdentifier>,AuthenticationTokenIdentifier> pair =
        secretManager.generateToken(principal, cfg);

    assertNotNull(pair);
    Token<AuthenticationTokenIdentifier> token = pair.getKey();
    assertNotNull(token);
    assertEquals(AuthenticationTokenIdentifier.TOKEN_KIND, token.getKind());

    // Reconstitute the token identifier (will happen when clients are involved)
    AuthenticationTokenIdentifier id = new AuthenticationTokenIdentifier();
    id.readFields(new DataInputStream(new ByteArrayInputStream(token.getIdentifier())));
    long now = System.currentTimeMillis();

    // Issue date should be after the test started, but before we deserialized the token
    assertTrue(id.getIssueDate() <= now,
        "Issue date did not fall within the expected upper bound. Expected less than " + now
            + ", but was " + id.getIssueDate());
    assertTrue(id.getIssueDate() >= then,
        "Issue date did not fall within the expected lower bound. Expected greater than " + then
            + ", but was " + id.getIssueDate());

    // Expiration is the token lifetime plus the issue date
    assertEquals(id.getIssueDate() + tokenLifetime, id.getExpirationDate());

    // Verify instance ID
    assertEquals(instanceId, id.getInstanceId());

    // The returned id should be the same as the reconstructed id
    assertEquals(pair.getValue(), id);
  }

  @Test
  public void testVerifyPassword() throws Exception {
    // start of the test
    long then = System.currentTimeMillis();

    // 1 minute
    long tokenLifetime = MINUTES.toMillis(1);
    AuthenticationTokenSecretManager secretManager =
        new AuthenticationTokenSecretManager(instanceId, tokenLifetime);

    // Add a current key
    secretManager
        .addKey(new AuthenticationKey(1, then, then + tokenLifetime, keyGen.generateKey()));

    String principal = "user@EXAMPLE.COM";
    Entry<Token<AuthenticationTokenIdentifier>,AuthenticationTokenIdentifier> pair =
        secretManager.generateToken(principal, cfg);
    Token<AuthenticationTokenIdentifier> token = pair.getKey();

    AuthenticationTokenIdentifier id = new AuthenticationTokenIdentifier();
    id.readFields(new DataInputStream(new ByteArrayInputStream(token.getIdentifier())));

    byte[] password = secretManager.retrievePassword(id);

    // The passwords line up against multiple calls with the same ID
    assertArrayEquals(password, secretManager.retrievePassword(id));

    // Sleep 50 ms to make sure we generate another token for the test
    // System.currentTimeMillis() is used as part of the token generation and if
    // the test runs fast enough it can return the same value that was used
    // when generating the first token and the test will fail
    Thread.sleep(50);

    // Make a second token for the same user
    // Briefly sleep to guarantee token is unique, since the token is based on the time
    Thread.sleep(100);
    Entry<Token<AuthenticationTokenIdentifier>,AuthenticationTokenIdentifier> pair2 =
        secretManager.generateToken(principal, cfg);
    Token<AuthenticationTokenIdentifier> token2 = pair2.getKey();
    // Reconstitute the token identifier (will happen when clients are involved)
    AuthenticationTokenIdentifier id2 = new AuthenticationTokenIdentifier();
    id2.readFields(new DataInputStream(new ByteArrayInputStream(token2.getIdentifier())));

    // Get the password
    byte[] password2 = secretManager.retrievePassword(id2);

    // It should be different than the password for the first user.
    assertFalse(Arrays.equals(password, password2),
        "Different tokens for the same user shouldn't have the same password");
  }

  @Test
  public void testExpiredPasswordsThrowError() throws Exception {
    // start of the test
    long then = System.currentTimeMillis();

    // 500ms lifetime
    long tokenLifetime = 500;
    AuthenticationTokenSecretManager secretManager =
        new AuthenticationTokenSecretManager(instanceId, tokenLifetime);

    // Add a current key
    secretManager
        .addKey(new AuthenticationKey(1, then, then + tokenLifetime, keyGen.generateKey()));

    String principal = "user@EXAMPLE.COM";
    Entry<Token<AuthenticationTokenIdentifier>,AuthenticationTokenIdentifier> pair =
        secretManager.generateToken(principal, cfg);
    Token<AuthenticationTokenIdentifier> token = pair.getKey();

    // Add a small buffer to make sure we move past the expiration of 0 for the token.
    Thread.sleep(1000);

    // Reconstitute the token identifier (will happen when clients are involved)
    AuthenticationTokenIdentifier id = new AuthenticationTokenIdentifier();
    id.readFields(new DataInputStream(new ByteArrayInputStream(token.getIdentifier())));

    assertThrows(InvalidToken.class, () -> secretManager.retrievePassword(id));
  }

  @Test
  public void testTokenIssuedInFuture() throws Exception {
    // start of the test
    long then = System.currentTimeMillis();

    long tokenLifetime = MINUTES.toMillis(1);
    AuthenticationTokenSecretManager secretManager =
        new AuthenticationTokenSecretManager(instanceId, tokenLifetime);

    // Add a current key
    secretManager
        .addKey(new AuthenticationKey(1, then, then + tokenLifetime, keyGen.generateKey()));

    String principal = "user@EXAMPLE.COM";
    Entry<Token<AuthenticationTokenIdentifier>,AuthenticationTokenIdentifier> pair =
        secretManager.generateToken(principal, cfg);
    Token<AuthenticationTokenIdentifier> token = pair.getKey();

    // Reconstitute the token identifier (will happen when clients are involved)
    AuthenticationTokenIdentifier id = new AuthenticationTokenIdentifier();
    id.readFields(new DataInputStream(new ByteArrayInputStream(token.getIdentifier())));

    // Increase the value of issueDate
    id.setIssueDate(Long.MAX_VALUE);

    assertThrows(InvalidToken.class, () -> secretManager.retrievePassword(id));
  }

  @Test
  public void testRolledManagerKey() throws Exception {
    // start of the test
    long then = System.currentTimeMillis();

    long tokenLifetime = MINUTES.toMillis(1);
    AuthenticationTokenSecretManager secretManager =
        new AuthenticationTokenSecretManager(instanceId, tokenLifetime);

    // Add a current key
    AuthenticationKey authKey1 =
        new AuthenticationKey(1, then, then + tokenLifetime, keyGen.generateKey());
    secretManager.addKey(authKey1);

    String principal = "user@EXAMPLE.COM";
    Entry<Token<AuthenticationTokenIdentifier>,AuthenticationTokenIdentifier> pair =
        secretManager.generateToken(principal, cfg);
    Token<AuthenticationTokenIdentifier> token = pair.getKey();

    AuthenticationTokenIdentifier id = new AuthenticationTokenIdentifier();
    id.readFields(new DataInputStream(new ByteArrayInputStream(token.getIdentifier())));

    long now = System.currentTimeMillis();
    secretManager.addKey(new AuthenticationKey(2, now, now + tokenLifetime, keyGen.generateKey()));

    // Should succeed -- the SecretManager still has authKey1
    secretManager.retrievePassword(id);

    // Remove authKey1
    secretManager.removeKey(authKey1.getKeyId());

    // Should fail -- authKey1 (presumably) expired, cannot authenticate
    assertThrows(InvalidToken.class, () -> secretManager.retrievePassword(id));
  }

  @Test
  @Timeout(20)
  public void testManagerKeyExpiration() throws Exception {
    ZooAuthenticationKeyDistributor keyDistributor =
        createMock(ZooAuthenticationKeyDistributor.class);
    // start of the test
    long then = System.currentTimeMillis();

    // 10s lifetime
    long tokenLifetime = 10_000L;
    AuthenticationTokenSecretManager secretManager =
        new AuthenticationTokenSecretManager(instanceId, tokenLifetime);

    // Make 2 keys, and add only one. The second has double the expiration of the first
    AuthenticationKey authKey1 =
        new AuthenticationKey(1, then, then + tokenLifetime, keyGen.generateKey());
    AuthenticationKey authKey2 = new AuthenticationKey(2, then + tokenLifetime,
        then + tokenLifetime * 2, keyGen.generateKey());
    secretManager.addKey(authKey1);

    keyDistributor.remove(authKey1);
    expectLastCall().once();

    replay(keyDistributor);

    // Make sure expiration doesn't trigger anything yet
    assertEquals(0, secretManager.removeExpiredKeys(keyDistributor));
    assertEquals(1, secretManager.getKeys().size());

    // Add the second key, still no expiration
    secretManager.addKey(authKey2);
    assertEquals(0, secretManager.removeExpiredKeys(keyDistributor));
    assertEquals(2, secretManager.getKeys().size());
    assertEquals(authKey2, secretManager.getCurrentKey());

    // Wait for the expiration
    long now = System.currentTimeMillis();
    while (now - (then + tokenLifetime) < 0) {
      Thread.sleep(500);
      now = System.currentTimeMillis();
    }

    // Expire the first
    assertEquals(1, secretManager.removeExpiredKeys(keyDistributor));

    // Ensure the second still exists
    assertEquals(1, secretManager.getKeys().size());
    assertEquals(authKey2, getOnlyElement(secretManager.getKeys().values()));
    assertEquals(authKey2, secretManager.getCurrentKey());

    verify(keyDistributor);
  }

  @Test
  public void testRestrictExpirationDate() throws Exception {
    // start of the test
    long then = System.currentTimeMillis();

    // 1 hr
    long tokenLifetime = HOURS.toMillis(1);
    AuthenticationTokenSecretManager secretManager =
        new AuthenticationTokenSecretManager(instanceId, tokenLifetime);

    // Add a current key
    secretManager
        .addKey(new AuthenticationKey(1, then, then + tokenLifetime, keyGen.generateKey()));

    // 1 minute
    cfg.setTokenLifetime(1, MINUTES);

    String principal = "user@EXAMPLE.COM";
    Entry<Token<AuthenticationTokenIdentifier>,AuthenticationTokenIdentifier> pair =
        secretManager.generateToken(principal, cfg);

    assertNotNull(pair);

    long now = System.currentTimeMillis();
    long actualExpiration = pair.getValue().getExpirationDate();
    long approximateLifetime = actualExpiration - now;

    log.info("actualExpiration={}, approximateLifetime={}", actualExpiration, approximateLifetime);

    // We don't know the exact lifetime, but we know that it can be no more than what was requested
    assertTrue(approximateLifetime <= cfg.getTokenLifetime(TimeUnit.MILLISECONDS),
        "Expected lifetime to be on thet order of the token lifetime, but was "
            + approximateLifetime);
  }

  @Test
  public void testInvalidRequestedExpirationDate() throws Exception {
    // start of the test
    long then = System.currentTimeMillis();

    // 1 hr
    long tokenLifetime = HOURS.toMillis(1);
    AuthenticationTokenSecretManager secretManager =
        new AuthenticationTokenSecretManager(instanceId, tokenLifetime);

    // Add a current key
    secretManager
        .addKey(new AuthenticationKey(1, then, then + tokenLifetime, keyGen.generateKey()));

    // A longer timeout than the secret key has
    cfg.setTokenLifetime(tokenLifetime + 1, TimeUnit.MILLISECONDS);

    // Should throw an exception
    assertThrows(AccumuloException.class,
        () -> secretManager.generateToken("user@EXAMPLE.COM", cfg));
  }
}
