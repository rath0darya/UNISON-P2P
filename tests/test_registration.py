import base64
import hashlib
import unittest

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils

from server import canonical_public, verify_registration


def b64url(raw):
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def identity_fixture():
    private = ec.generate_private_key(ec.SECP256R1())
    numbers = private.public_key().public_numbers()
    jwk = {
        "crv": "P-256",
        "kty": "EC",
        "x": b64url(numbers.x.to_bytes(32, "big")),
        "y": b64url(numbers.y.to_bytes(32, "big")),
    }
    digest = hashlib.sha256(canonical_public(jwk).encode("utf-8")).digest()
    peer_id = base64.b32encode(digest).decode("ascii").rstrip("=")[:20]
    return private, jwk, peer_id


def proof_signature(private, peer_id, challenge):
    message = f"UNISON-P2P-REGISTER-v1:{peer_id}:{challenge}".encode("utf-8")
    der = private.sign(message, ec.ECDSA(hashes.SHA256()))
    r, s = utils.decode_dss_signature(der)
    return base64.b64encode(r.to_bytes(32, "big") + s.to_bytes(32, "big")).decode("ascii")


class RegistrationProofTests(unittest.TestCase):
    def test_valid_identity_proof_is_accepted(self):
        private, jwk, peer_id = identity_fixture()
        challenge = "fresh-random-challenge"
        signature = proof_signature(private, peer_id, challenge)
        self.assertTrue(verify_registration(peer_id, jwk, signature, challenge))

    def test_replayed_proof_for_different_challenge_is_rejected(self):
        private, jwk, peer_id = identity_fixture()
        signature = proof_signature(private, peer_id, "challenge-one")
        self.assertFalse(verify_registration(peer_id, jwk, signature, "challenge-two"))

    def test_public_key_cannot_register_another_peer_id(self):
        private, jwk, peer_id = identity_fixture()
        signature = proof_signature(private, peer_id, "challenge")
        other_id = ("A" if peer_id[0] != "A" else "B") + peer_id[1:]
        self.assertFalse(verify_registration(other_id, jwk, signature, "challenge"))

    def test_malformed_signature_is_rejected(self):
        _, jwk, peer_id = identity_fixture()
        self.assertFalse(verify_registration(peer_id, jwk, "not-base64!", "challenge"))


if __name__ == "__main__":
    unittest.main()
