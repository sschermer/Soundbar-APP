import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import signal_diagnose as diag


class SignalDiagTests(unittest.TestCase):
    def test_irrelevant_response_skipped(self):
        assert diag.parse_response({'result': 'ok', 'msg': 'PRODUCT_INFO', 'data': {'serial': 'x'}}) is None

    def test_signal_field_preserved(self):
        o = diag.parse_response({'result': 'ok', 'msg': 'SPK_LIST_VIEW_INFO', 'data': {'s_audio_source': 'DOLBY AUDIO', 'i_curr_func': 20}})
        assert o == ('SPK_LIST_VIEW_INFO', {'s_audio_source': 'DOLBY AUDIO', 'i_curr_func': 20})

    def test_personal_fields_redacted(self):
        o = diag.parse_response({'result': 'ok', 'msg': 'SPK_LIST_VIEW_INFO', 'data': {'s_audio_source': 'PCM', 's_user_name': 'John', 's_ipv4_addr': '192.168.1.23', 'identifier': 'AA:BB:CC:DD:EE:FF'}})
        assert 's_user_name' not in o[1]
        assert 's_ipv4_addr' not in o[1]
        assert o[1]['identifier'] == '[MAC ausgeblendet]'

    def test_no_channel_inference(self):
        result = diag.state_summary({'SPK_LIST_VIEW_INFO': {'s_audio_source': 'DOLBY AUDIO'}, 'EQ_VIEW_INFO': {'i_curr_eq': 15}})
        assert result['audio_source_rohwert'] == 'DOLBY AUDIO'
        assert '5.1' in result['hinweis']
        assert 'Dolby Atmos' in result['klangmodus_bezeichnung_nur_zur_orientierung']

    def test_ip_removed_from_error_text(self):
        assert '192.168.1.23' not in diag.scrub_text('network 192.168.1.23 connection error')


if __name__ == '__main__':
    unittest.main()