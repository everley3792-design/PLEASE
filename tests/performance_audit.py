"""Reproducible Chromium frame / CPU measurements; not an Android FPS benchmark."""
import argparse, functools, http.server, json, threading, time
from pathlib import Path
from playwright.sync_api import sync_playwright
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--chromium',default=None);p.add_argument('--seconds',type=int,default=10);p.add_argument('--throttle',type=int,default=4);args=p.parse_args()
class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self,*_):pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(QuietHandler,directory=str(root/'app/src/main/assets')))
threading.Thread(target=server.serve_forever,daemon=True).start()
with sync_playwright() as api:
    b=api.chromium.launch(executable_path=args.chromium,headless=True,args=['--no-sandbox'])
    c=b.new_context(viewport={'width':412,'height':892},device_scale_factor=1,is_mobile=True,has_touch=True)
    page=c.new_page();page.goto(f'http://127.0.0.1:{server.server_port}/index.html');page.wait_for_function('OCPolish.ready && !document.getElementById("splash")')
    page.wait_for_selector('.ground-texture');page.wait_for_timeout(1200)
    session=c.new_cdp_session(page);session.send('Performance.enable');session.send('Emulation.setCPUThrottlingRate',{'rate':args.throttle})
    def metrics():return {m['name']:m['value'] for m in session.send('Performance.getMetrics')['metrics']}
    page.evaluate('''window._frames=[];window._last=0;window._sample=true;requestAnimationFrame(function frame(t){if(_last)_frames.push(t-_last);_last=t;if(_sample)requestAnimationFrame(frame)})''')
    before=metrics();start=time.monotonic();page.wait_for_timeout(args.seconds*1000);after=metrics()
    frames=sorted(page.evaluate('(_sample=false,_frames)'))
    result={'seconds':round(time.monotonic()-start,2),'cpuThrottle':args.throttle,'viewport':'412x892','samples':len(frames),'meanMs':round(sum(frames)/len(frames),2),'p95Ms':round(frames[int(len(frames)*.95)],2),'over50ms':sum(t>50 for t in frames),'durations':{k:round(after[k]-before[k],4) for k in ['TaskDuration','ScriptDuration','LayoutDuration','RecalcStyleDuration']},'dom':page.evaluate('document.querySelectorAll("*").length')}
    print(json.dumps(result,indent=2));b.close()
server.shutdown()
