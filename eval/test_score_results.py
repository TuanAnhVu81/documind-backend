import unittest
from score_results import score


class ScoreResultsTest(unittest.TestCase):
    def setUp(self):
        self.questions = [
            {'id': 'q1', 'answerable': True, 'expectedSources': [{}, {}], 'expectedClaims': [{}, {}]},
            {'id': 'q2', 'answerable': False, 'expectedSources': [], 'expectedClaims': []},
        ]
        self.run = {'judgementMethod': 'human', 'metadata': {'topK': 2}, 'cases': [
            {'id': 'q1', 'status': 'COMPLETE', 'outcome': 'ANSWER', 'answerCorrect': True,
             'judgementNotes': 'Nguồn thứ nhất hỗ trợ một nhận định.',
             'retrieved': [{'supportedSourceIndices': []}, {'supportedSourceIndices': [0]}],
             'citations': [{'id': 'S1', 'validId': True, 'supportsAnswer': True, 'supportedClaimIndices': [0]}]},
            {'id': 'q2', 'status': 'ERROR', 'outcome': 'PROVIDER_ERROR', 'answerCorrect': False,
             'judgementNotes': 'Lỗi kỹ thuật không phải từ chối đúng.', 'retrieved': [], 'citations': []},
        ]}

    def test_reports_fractional_recall_and_does_not_count_provider_error_as_refusal(self):
        metrics = score(self.questions, self.run)['metrics']
        self.assertEqual(metrics['sourceHitAt3'], [1, 1])
        self.assertEqual(metrics['retrievalRecallAtK'], 0.5)
        self.assertEqual(metrics['mrrAtK'], 0.5)
        self.assertEqual(metrics['claimCitationRecall'], [1, 2])
        self.assertEqual(metrics['correctRefusal'], [0, 1])

    def test_rejects_fabricated_source_mapping(self):
        self.run['cases'][0]['retrieved'][0]['supportedSourceIndices'] = [4]
        with self.assertRaises(ValueError):
            score(self.questions, self.run)


if __name__ == '__main__':
    unittest.main()
