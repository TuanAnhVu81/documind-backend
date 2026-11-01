"""Regression checks for mislabeled evidence, stale audits and leaked history answers."""
import copy
import json
import unittest
from pathlib import Path

from question_suite import NO_EVIDENCE_TOPICS, corpus_fingerprint, find_topic_hits
from validate import validate_no_evidence_audit, validate_question

ROOT = Path(__file__).resolve().parent


class EvidenceContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
        cls.documents = {document["id"]: document for document in manifest["documents"]}
        cls.canonical = {document["id"]: json.loads((ROOT / document["canonicalPath"]).read_text(encoding="utf-8"))["paragraphs"]
                         for document in manifest["documents"]}
        cls.questions = json.loads((ROOT / "questions.json").read_text(encoding="utf-8"))
        cls.audit = json.loads((ROOT / "no_evidence_audit.json").read_text(encoding="utf-8"))

    def check(self, question):
        validate_question(question, self.documents, self.canonical)

    def test_same_document_with_two_distinct_passages_is_valid(self):
        question = self.questions[5]
        self.assertEqual(len(question["documentFixtureIds"]), 1)
        self.check(question)

    def test_legacy_multi_source_label_is_rejected(self):
        question = copy.deepcopy(self.questions[5])
        question["kind"] = "multi_source"
        with self.assertRaisesRegex(AssertionError, "ambiguous kind"):
            self.check(question)

    def test_multi_passage_cannot_be_hidden_under_direct_label(self):
        question = copy.deepcopy(self.questions[5])
        question["kind"] = "direct"
        with self.assertRaisesRegex(AssertionError, "direct must use one passage"):
            self.check(question)

    def test_two_quotes_in_one_passage_do_not_satisfy_multi_passage(self):
        # Q13 has two different quote offsets but only one DOCX paragraph.
        question = copy.deepcopy(self.questions[12])
        question["kind"] = "multi_passage"
        with self.assertRaisesRegex(AssertionError, "distinct passages"):
            self.check(question)

    def test_each_passage_needs_its_own_claim(self):
        question = copy.deepcopy(self.questions[5])
        question["expectedClaims"] = [{
            "text": "A combined annotation is not enough to prove both passages are required.",
            "sourceIndices": [0, 1], "evidenceFragments": ["13 ngày", "250 đô la Mỹ"],
            "answerFragments": ["13 ngày", "250 đô la Mỹ"],
        }]
        with self.assertRaisesRegex(AssertionError, "own evidence-backed claim"):
            self.check(question)

    def test_answer_claim_cannot_point_to_unrelated_evidence(self):
        question = copy.deepcopy(self.questions[5])
        question["expectedClaims"][1]["sourceIndices"] = [0]
        with self.assertRaisesRegex(AssertionError, "evidence fragment missing"):
            self.check(question)

    def test_no_evidence_cannot_be_limited_to_one_document(self):
        question = copy.deepcopy(self.questions[20])
        question["scopeMode"] = "SELECTED"
        question["documentFixtureIds"] = ["handbook-01"]
        with self.assertRaisesRegex(AssertionError, "NO_EVIDENCE must cover"):
            self.check(question)

    def test_answer_already_disclosed_in_history_is_rejected(self):
        question = copy.deepcopy(self.questions[0])
        question["history"][1]["content"] = question["expectedAnswer"]
        with self.assertRaisesRegex(AssertionError, "already disclosed"):
            self.check(question)

    def test_stale_full_corpus_audit_is_rejected(self):
        audit = copy.deepcopy(self.audit)
        audit["corpusTextSha256"] = "0" * 64
        with self.assertRaisesRegex(AssertionError, "fingerprint is stale"):
            validate_no_evidence_audit(audit, self.questions, self.documents, self.canonical)

    def test_probe_scan_checks_last_document_and_normalizes_case_and_spacing(self):
        canonical = copy.deepcopy(self.canonical)
        canonical["handbook-20"][4] += " TÀI KHOẢN\n NGÂN HÀNG: 123456789."
        hits = find_topic_hits(canonical, NO_EVIDENCE_TOPICS["bank_account"])
        self.assertTrue(hits)
        self.assertTrue(all(hit["fixtureId"] == "handbook-20" and hit["canonicalSection"] == 5 for hit in hits))

    def test_probe_matches_word_boundaries_not_substrings(self):
        self.assertEqual(find_topic_hits({"sample": ["biosignal"]}, ["iOS"]), [])

    def test_new_topic_evidence_requires_review_even_after_audit_hash_refresh(self):
        canonical = copy.deepcopy(self.canonical)
        canonical["handbook-20"][4] += " Số tài khoản ngân hàng nhận tiền là 123456789."
        audit = copy.deepcopy(self.audit)
        audit["corpusTextSha256"] = corpus_fingerprint(canonical)
        for case in audit["cases"]:
            case["lexicalHits"] = find_topic_hits(canonical, case["probeTerms"])
        with self.assertRaisesRegex(AssertionError, "manually reassess NO_EVIDENCE"):
            validate_no_evidence_audit(audit, self.questions, self.documents, canonical)


if __name__ == "__main__":
    unittest.main()
