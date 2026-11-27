import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import time

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

import chatgpt_plan as plan


class LocalOAuthTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = patch.object(plan, "ROOT", Path(self.directory.name))
        self.root.start()
        self.addCleanup(self.root.stop)
        self.vault = {}
        getter = patch.object(plan.keyring, "get_password", side_effect=lambda s, a: self.vault.get((s, a)))
        setter = patch.object(plan.keyring, "set_password", side_effect=lambda s, a, v: self.vault.update({(s, a): v}))
        getter.start()
        setter.start()
        self.addCleanup(getter.stop)
        self.addCleanup(setter.stop)

    def test_record_is_encrypted_and_host_is_stable(self):
        value = {"access_token": "example-secret", "client_id": "oaiapp_example"}
        plan.save(value)
        self.assertEqual(value, plan.load())
        self.assertNotIn(b"example-secret", (plan.ROOT / "session.enc").read_bytes())
        self.assertEqual(plan.host_id(), plan.host_id())
        self.assertTrue(plan.host_id().startswith("urn:uuid:"))
        plan.save(dict(value, access_token="replacement"))
        self.assertEqual("replacement", plan.load()["access_token"])

    def test_id_token_checks_signature_issuer_audience_and_nonce(self):
        private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        claims = {"iss": plan.ISSUER, "aud": "oaiapp_example", "sub": "subject",
                  "exp": int(time.time()) + 300, "nonce": "expected"}
        def encode(**changes):
            return jwt.encode(dict(claims, **changes), private, algorithm="RS256")
        with patch.object(plan.jwt, "PyJWKClient") as jwks:
            jwks.return_value.get_signing_key_from_jwt.return_value.key = private.public_key()
            self.assertEqual("subject", plan.token_claims(encode(), "oaiapp_example", "expected")["sub"])
            with self.assertRaises(RuntimeError):
                plan.token_claims(encode(nonce="wrong"), "oaiapp_example", "expected")
            with self.assertRaises(jwt.InvalidAudienceError):
                plan.token_claims(encode(aud="another"), "oaiapp_example", "expected")
            with self.assertRaises(jwt.InvalidIssuerError):
                plan.token_claims(encode(iss="https://example.org"), "oaiapp_example", "expected")
            with self.assertRaises(jwt.ExpiredSignatureError):
                plan.token_claims(encode(exp=1), "oaiapp_example", "expected")

    def test_refresh_rotates_tokens_and_preserves_granted_scope(self):
        plan.save({"client_id": "oaiapp_example", "subject": "subject",
                   "scopes": [plan.PLAN_SCOPE], "access_token": "old",
                   "refresh_token": "refresh-old", "expires_at": 0})
        with patch.object(plan, "request_json", return_value={
                "token_type": "Bearer", "access_token": "new", "refresh_token": "refresh-new",
                "expires_in": 3600}) as request:
            self.assertEqual("new", plan.access_token())
            self.assertEqual("refresh-new", plan.load()["refresh_token"])
            self.assertEqual("new", plan.access_token())
            request.assert_called_once_with(plan.TOKEN, {
                "grant_type": "refresh_token", "client_id": "oaiapp_example",
                "refresh_token": "refresh-old", "resource": plan.RESOURCE})

    def test_scope_removal_stops_inference(self):
        plan.save({"client_id": "oaiapp_example", "subject": "subject",
                   "scopes": [plan.PLAN_SCOPE], "access_token": "old",
                   "refresh_token": "refresh-old", "expires_at": 0})
        with patch.object(plan, "request_json", return_value={
                "token_type": "Bearer", "access_token": "new", "scope": "openid", "expires_in": 3600}):
            with self.assertRaises(RuntimeError):
                plan.access_token()
        self.assertNotIn(plan.PLAN_SCOPE, plan.load()["scopes"])

    def test_account_change_rejected_without_overwriting_credentials(self):
        original = {"client_id": "oaiapp_example", "subject": "original"}
        plan.save(original)
        with patch.object(plan, "token_claims", return_value={"sub": "different"}):
            with self.assertRaises(RuntimeError):
                plan.update_tokens(original, {"id_token": "example"}, "nonce", initial=True)
        self.assertEqual(original, plan.load())

    def test_logout_revokes_and_removes_tokens_but_keeps_registration(self):
        plan.save({"client_id": "oaiapp_example", "subject": "subject",
                   "refresh_token": "refresh", "access_token": "access",
                   "id_token": "id", "email": "example@example.org"})
        endpoint = plan.ISSUER + "/revoke"
        with patch.object(plan, "request_json", side_effect=[{"revocation_endpoint": endpoint}, {}]) as request:
            plan.logout()
            request.assert_called_with(endpoint, {"token": "refresh", "token_type_hint": "refresh_token",
                                                   "client_id": "oaiapp_example"})
        self.assertEqual({"client_id": "oaiapp_example", "subject": "subject", "email": "example@example.org"},
                         plan.load())


if __name__ == "__main__":
    unittest.main()
