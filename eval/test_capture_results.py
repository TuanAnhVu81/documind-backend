import io
import unittest
from capture_results import local_base, parse_sse


class CaptureResultsTest(unittest.TestCase):
    def test_sse_uses_complete_events_and_unicode(self):
        stream = io.BytesIO('event: token\ndata: {"text":"Việt"}\n\nevent: done\ndata: {"message":{}}\n\n'.encode())
        self.assertEqual(list(parse_sse(stream)), [('token', {'text': 'Việt'}), ('done', {'message': {}})])

    def test_only_accepts_loopback_origins(self):
        self.assertEqual(local_base('http://127.0.0.1:8080/'), 'http://127.0.0.1:8080')
        for url in ['https://example.com', 'http://localhost.example.com', 'http://localhost:8080/api',
                    'http://user:password@localhost', 'http://localhost/?x=1']:
            with self.assertRaises(ValueError):
                local_base(url)


if __name__ == '__main__':
    unittest.main()
