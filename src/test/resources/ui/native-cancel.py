"""Drive the production entry through a real controlling PTY, including ExecPty's stty subprocesses."""
import errno
import fcntl
import json
import os
import pathlib
import pty
import select
import signal
import struct
import subprocess
import sys
import termios
import time

root = pathlib.Path(sys.argv[3])
root.mkdir(parents=True, exist_ok=True)
environment = os.environ.copy()
for key in list(environment):
    if key.startswith(('CAFECLI_', 'PAICLI_')) or key in (
            'NO_COLOR', 'GLM_API_KEY', 'DEEPSEEK_API_KEY', 'STEP_API_KEY', 'KIMI_API_KEY',
            'FREELLMAPI_API_KEY', 'MOONSHOT_API_KEY', 'SEARCH_PROVIDER', 'SERPAPI_KEY', 'SEARXNG_URL'):
        environment.pop(key)
environment['TERM'] = 'xterm-256color'


class Entry:
    def __init__(self, case, responses):
        self.case = case
        self.raw = bytearray()
        self.queries = 0
        (case / 'home').mkdir(parents=True)
        (case / 'project').mkdir()
        script = case / 'script.json'
        script.write_text(json.dumps({'responses': responses}))
        self.master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 40, 100, 0, 0))

        def controlling_terminal():
            os.setsid()
            fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

        properties = {
            'user.home': str(case / 'home'), 'cafecli.provider': 'fake',
            'cafecli.fake.script': str(script), 'cafecli.snapshot.enabled': 'false',
            'paicli.render.synchronized-output': 'false', 'paicli.render.osc8': 'false',
            'file.encoding': 'UTF-8',
        }
        command = [sys.argv[1], *('-D' + key + '=' + value for key, value in properties.items()),
                   '-cp', sys.argv[2], 'com.cafecli.cli.Main']
        self.process = subprocess.Popen(command, cwd=case / 'project', env=environment,
                                        stdin=slave, stdout=slave, stderr=slave,
                                        preexec_fn=controlling_terminal, close_fds=True)
        os.close(slave)

    def pump(self, timeout=.1):
        if not select.select([self.master], [], [], timeout)[0]:
            return
        try:
            self.raw.extend(os.read(self.master, 65536))
        except OSError as failure:
            if failure.errno == errno.EIO:
                return
            raise
        # Answer terminal cursor queries exactly as the other native entry fixtures do.
        count = self.raw.count(b'\x1b[6n')
        while self.queries < count:
            self.send(b'\x1b[1;1R')
            self.queries += 1

    def expect(self, pattern, offset=0, timeout=10):
        pattern = pattern.encode() if isinstance(pattern, str) else pattern
        deadline = time.monotonic() + timeout
        while pattern not in self.raw[offset:] and time.monotonic() < deadline and self.process.poll() is None:
            self.pump()
        assert pattern in self.raw[offset:], (pattern, str(self.case), bytes(self.raw[-3000:]), self.process.poll())

    def send(self, keys):
        os.write(self.master, keys.encode() if isinstance(keys, str) else keys)

    def finish(self):
        deadline = time.monotonic() + 8
        last_eof = 0
        while self.process.poll() is None and time.monotonic() < deadline:
            if time.monotonic() - last_eof > .3:
                self.send(b'\x04')
                last_eof = time.monotonic()
            self.pump()
        assert self.process.poll() == 0, (self.process.poll(), str(self.case), bytes(self.raw[-3000:]))

    def close(self):
        (self.case / 'terminal.bin').write_bytes(self.raw)
        if self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGKILL)
            self.process.wait(timeout=5)
        os.close(self.master)


def write_call(identifier, path, content):
    return {'id': identifier, 'name': 'write_file', 'arguments': {'path': path, 'content': content}}


case = root / 'cancel'
case.mkdir()
entry = Entry(case, [
    {'toolCalls': [write_call('first', 'first.txt', 'written')]}, {'text': 'FIRSTDONE\n'},
    {'toolCalls': [write_call('cancel', 'cancel.txt', 'must not execute')]},
])
# A timed-out parent asks Python to unwind this lifetime before it resorts to forcible termination.
signal.signal(signal.SIGTERM, lambda *_: sys.exit(1))
try:
    entry.expect('YOLO Ctrl+Y to enable HITL')
    entry.send(b'\x19')
    entry.expect('HITL Ctrl+Y for YOLO')
    entry.send('first approval\r')
    entry.expect('HITL approval')
    entry.send('y\r')
    entry.expect('FIRSTDONE')
    offset = len(entry.raw)
    entry.send('cancel approval\r')
    entry.expect('HITL approval', offset)
    # Intentionally do not wait for a later right-prompt marker: this is the native setup/cleanup race.
    entry.send(b'PRESERVEDDRAFT\x03')
    entry.expect('已取消当前任务', offset)
    assert not (case / 'project/cancel.txt').exists()
    assert entry.process.poll() is None
    entry.send(b'\x15')
    entry.finish()
    print('PASS', root)
finally:
    entry.close()
