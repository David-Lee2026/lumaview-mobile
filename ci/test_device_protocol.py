import unittest
from ci.run_device_tests import parse_instrumentation

class DeviceProtocolTest(unittest.TestCase):
    def sample(self, code=0, ending='OK (1 test)\nINSTRUMENTATION_CODE: -1\n'):
        return ('INSTRUMENTATION_STATUS: class=org.test.Example\n'
                'INSTRUMENTATION_STATUS: test=actualCase\n'
                f'INSTRUMENTATION_STATUS_CODE: {code}\n'+ending)
    def test_records_observed_case(self):
        r=parse_instrumentation(self.sample(), {'org.test.Example#actualCase'})
        self.assertEqual(r, [{'test':'org.test.Example#actualCase','statusCode':0}])
    def test_rejects_failure_even_with_zero_adb_exit(self):
        with self.assertRaises(ValueError):parse_instrumentation(self.sample(-2), {'org.test.Example#actualCase'})
    def test_rejects_missing_test(self):
        with self.assertRaises(ValueError):parse_instrumentation(self.sample(), {'org.test.Example#actualCase','org.test.Example#missing'})
    def test_rejects_instrumentation_crash(self):
        with self.assertRaises(ValueError):parse_instrumentation('INSTRUMENTATION_FAILED: app crashed\n', {'org.test.Example#actualCase'})
    def test_rejects_truncated_result(self):
        with self.assertRaises(ValueError):parse_instrumentation(self.sample(ending=''), {'org.test.Example#actualCase'})
    def test_rejects_duplicate_case(self):
        with self.assertRaises(ValueError):parse_instrumentation(self.sample()+self.sample(), {'org.test.Example#actualCase'})

if __name__=='__main__':unittest.main()
