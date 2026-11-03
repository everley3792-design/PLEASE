"""Browser integration checks. Native platform UI still requires an Android device.

Run: pip install playwright && playwright install chromium
     python tests/web_regression.py --chromium /usr/bin/chromium
"""
import argparse, base64, functools, http.server, json, threading
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--chromium', default=None)
args = parser.parse_args()
class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_): pass
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(QuietHandler, directory=str(ROOT / 'app/src/main/assets')))
threading.Thread(target=server.serve_forever, daemon=True).start()
base = f'http://127.0.0.1:{server.server_port}'
checks=[]
def check(name, condition):
    assert condition, name
    checks.append(name)
    print('PASS', name, flush=True)

with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=args.chromium, headless=True, args=['--no-sandbox'])
    context=browser.new_context(viewport={'width':412,'height':892},is_mobile=True,has_touch=True)
    page=context.new_page();errors=[]
    page.on('pageerror',lambda error:errors.append(str(error)))
    page.add_init_script('''
      window.__nativeCalls=[];
      window.OCNative={
        saveFile:(name,text)=>{__nativeCalls.push({method:'saveFile',name,text});return true},
        saveBase64:(...args)=>{__nativeCalls.push({method:'saveBase64',args});return true},
        shareBase64:(...args)=>{__nativeCalls.push({method:'shareBase64',args});setTimeout(()=>OCAndroid.finishRequest(args[3],true,''),20);return true},
        setTheme:()=>{},vibrate:()=>{},
        captureScene:name=>{__nativeCalls.push({method:'captureScene',name,toolbarHidden:getComputedStyle(document.querySelector('.l31-photo-bar')).visibility==='hidden'});setTimeout(()=>OCPolish.photoDone(),30);return true}
      };
    ''')
    page.goto(base+'/index.html')
    page.wait_for_function('window.OCPolish?.ready && !document.getElementById("splash")')
    page.wait_for_selector('.ground-texture')
    check('three residents and consolidated ground texture',page.evaluate('OCSCommunity.nodes.size===3 && Home.stage.root.querySelectorAll("*").length<1800'))
    check('five labelled navigation targets',page.locator('#nav .nav-btn:not(.nx-off) .nav-label').count()==5)
    # 3.3 rendering stability
    check('clouds drift without compositor animations',page.evaluate("!document.getAnimations().some(a=>a.effect?.target?.classList?.contains('cd')) && [...document.querySelectorAll('.cd')].some(c=>c.style.transform.startsWith('translate('))"))
    page.evaluate("Cfg.v.envMode='manual';Cfg.v.envWx='sunny';Env.target();Env.dirty=1")
    page.wait_for_function("Env.rt.find(e=>e.r===Home.stage.root).cv.style.display==='none'")
    check('idle weather canvas leaves the render tree',True)
    check('frame loop runs on whole display frames',page.evaluate('Number.isInteger(Ticker.step)&&Ticker.step>=1&&Ticker.step<=4'))
    page.evaluate('window.__homeUpdates=0;const u=Home.stage.update;Home.stage.update=function(){__homeUpdates++;return u.apply(this,arguments)};window.__restoreHomeUpdate=()=>{Home.stage.update=u}')
    page.locator('#nav [data-tab="1"]').click();page.wait_for_timeout(200);page.evaluate('__homeUpdates=0');page.wait_for_timeout(600)
    check('hidden home scene is not updated on other tabs',page.evaluate('__homeUpdates')==0)
    page.locator('#nav [data-tab="0"]').click();page.wait_for_timeout(600)
    check('home scene resumes on return',page.evaluate('__homeUpdates>0 || OCSCommunity.ready'))
    page.evaluate('__restoreHomeUpdate()')
    page.locator('#qbFab').tap();page.wait_for_timeout(450)
    check('open bag covers the camera button',page.evaluate("document.getElementById('qbag').classList.contains('open') && getComputedStyle(document.getElementById('camFollow')).visibility==='hidden'"))
    check('back closes the bag drawer',page.evaluate('OCApp.back() && !document.getElementById("qbag").classList.contains("open")'))
    for tab in [1,2,3,0,2,0]:
        page.locator(f'#nav [data-tab="{tab}"]').click()
        check(f'navigate to tab {tab}',page.evaluate('Nav.cur')==tab)
    page.evaluate('Settings.open()')
    check('world pauses underneath settings',page.evaluate('Env.rt.find(e=>e.r===Home.stage.root).suspended'))
    page.locator('.settings-quality [data-v="battery"]').click()
    check('battery quality selected',page.evaluate('document.documentElement.dataset.quality')=='battery')
    page.locator('.settings-quality [data-v="full"]').click()
    page.evaluate('OCApp.back()');page.wait_for_timeout(250)
    check('full quality restores original decoration',page.locator('.isle-ground .isle-tuft').count()>100)
    page.evaluate('Settings.open()');page.locator('.settings-quality [data-v="balanced"]').click();page.evaluate('OCApp.back()')
    page.wait_for_function('document.querySelector(".ground-texture:not([hidden])") && document.querySelectorAll(".isle-ground .isle-tuft").length===0')
    check('balanced quality restores cached scenery',True)
    page.evaluate('OCAndroid.resume(false)')
    check('native pause stops the shared animation frame',page.evaluate('Ticker.paused && Ticker.raf===0'))
    page.evaluate('OCAndroid.resume(true)')
    page.wait_for_function('!Ticker.paused && Ticker.raf!==0')
    check('native resume restarts the animation frame',True)
    page.locator('#hudRail>[data-go="toggle"]').click()
    check('daily drawer opens',page.locator('#hudRail').evaluate('(n)=>n.classList.contains("open")'))
    check('back closes drawer before leaving',page.evaluate('OCApp.back() && !document.getElementById("hudRail").classList.contains("open")'))
    page.locator('#homeChip').click();page.wait_for_selector('#l31Quick')
    check('back handles companion quick view',page.evaluate('OCApp.back()'));page.wait_for_selector('#l31Quick',state='detached');check('quick view is removed after its exit animation',True)
    camera_y=page.evaluate('''()=>{const n=OCSCommunity.nodes.get(Cfg.v.active);OCSCommunity.interrupt(n);n.next=OCSCommunity.clock+20;OCSCamera.setFollow(true);OCSCamera.focusTarget('focus');return OCSCamera.ty}''')
    page.evaluate('Home.stage.hop(500)');page.wait_for_function('Home.stage.h>15')
    check('a character hop does not move the island camera vertically',abs(page.evaluate('OCSCamera.ty')-camera_y)<2)
    page.evaluate('L31.photoMode()')
    page.locator('.l31-photo-bar [data-shot]').click()
    page.wait_for_function('__nativeCalls.some(c=>c.method==="captureScene")')
    check('photo requests a native capture with toolbar hidden',page.evaluate('__nativeCalls.find(c=>c.method==="captureScene").toolbarHidden'))
    check('back exits photo mode',page.evaluate('OCApp.back() && !document.body.classList.contains("l31-photo")'))
    # Image selection exercises the original file handler and IndexedDB writes.
    png=page.evaluate('''()=>{const c=document.createElement('canvas');c.width=c.height=48;const g=c.getContext('2d');g.fillStyle='#347E6C';g.fillRect(6,6,36,36);return c.toDataURL().split(',')[1]}''')
    page.locator('#fileIn').set_input_files({'name':'回歸測試夥伴.png','mimeType':'image/png','buffer':base64.b64decode(png)})
    page.wait_for_function('Chars.list.some(c=>c.name==="回歸測試夥伴")');page.wait_for_timeout(550)
    check('PNG becomes a persistent custom character',page.evaluate('Chars.list.find(c=>c.name==="回歸測試夥伴").rec.blob.size>0'))
    page.evaluate('while(Ov.stack.length)Ov.back()')
    page.evaluate('Backup.exportAll()');page.locator('#bkSave').click()
    exported=page.evaluate('__nativeCalls.filter(c=>c.method==="saveFile").at(-1)')
    backup=json.loads(exported['text'])
    check('full backup contains four characters and workspace',len(backup['chars'])==4 and bool(backup['workspace']))
    page.locator('#bkShare').click();page.wait_for_function('__nativeCalls.some(c=>c.method==="shareBase64")')
    check('backup sharing calls the Android bridge',True)
    page.evaluate('while(Ov.stack.length)Ov.back();Nav.go(0)')
    page.evaluate('Shop.open()');page.wait_for_selector('.tls-search input')
    search=page.locator('.tls-search input')
    search.fill('找不到的回歸測試商品');page.wait_for_timeout(300)
    check('shop search has a useful empty state',page.locator('.tls-empty').count()>0)
    search.fill('');page.wait_for_timeout(250)
    check('clearing search restores products',page.locator('.tls-card').count()>0)
    page.evaluate('Wallet.earn(500)')  # Isolated fixture: enough funds to exercise confirmation.
    candidate=page.evaluate('''()=>Cat.d.products.filter(p=>OCSShop.inStock(p)&&Cat.price(p)<=Wallet.bal()&&Cat.price(p)>0).sort((a,b)=>Cat.price(a)-Cat.price(b)).map(p=>({id:p.id,item:p.itemId,price:Cat.price(p),qty:Items.get(p.itemId).qty}))[0]''')
    check('an affordable product is available',bool(candidate))
    old_balance=page.evaluate('Wallet.bal()')
    page.evaluate('(id)=>Shop.detail(id)',candidate['id'])
    page.locator('.tls-detail [data-buy]').click();page.locator('.tls-detail [data-ok]').click();page.wait_for_timeout(300)
    check('confirmed purchase deducts the exact price',page.evaluate('Wallet.bal()')==old_balance-candidate['price'])
    check('confirmed purchase increments inventory',page.evaluate('(id)=>Items.get(id).qty',candidate['item'])==candidate['qty']+1)
    page.evaluate('while(Ov.stack.length)Ov.back();Nav.go(0);OCS24D.Ed.enter("room",Home.stage)')
    check('room editor opens without losing residents',page.evaluate('OCS24D.Ed.on && OCSCommunity.nodes.size===3'))
    page.evaluate('OCS24D.Ed.exit(false)');page.wait_for_timeout(300)
    check('room editor exits cleanly',page.evaluate('!OCS24D.Ed.on'))
    custom=page.evaluate('Chars.list.find(c=>c.kind==="custom").id')
    page.evaluate('(id)=>Ed.open(id,"asset")',custom);page.wait_for_timeout(300)
    check('custom character editor opens',page.evaluate('Ed.isOpen'))
    page.evaluate('OCApp.back()');page.wait_for_timeout(200)
    check('back closes custom character editor',page.evaluate('!Ed.isOpen'))
    # The revised path hit test must agree with the original SVG at local points.
    terrain=page.evaluate('''()=>{const p=IsleGround.sand;let mismatch=0;for(let x=0;x<=3000;x+=83)for(let y=0;y<=1000;y+=71){if(OCGraphics.contains(p,x,y)!==p.isPointInFill(new DOMPoint(x,y)))mismatch++}return mismatch}''')
    check('terrain hit testing preserves shoreline collisions',terrain==0)
    page.evaluate('Theme.set("dark");OCPolish.setQuality("battery");Cfg.flush()')
    page.reload();page.wait_for_function('window.OCPolish?.ready && !document.getElementById("splash")')
    check('theme and quality survive restart',page.evaluate('document.documentElement.dataset.theme==="dark" && document.documentElement.dataset.quality==="battery"'))
    check('custom image survives restart',page.evaluate('Chars.list.some(c=>c.kind==="custom"&&c.rec.blob.size>0)'))
    page.evaluate('Theme.set("light");OCPolish.setQuality("balanced")')
    for width,height in [(320,720),(412,892),(892,412)]:
        page.set_viewport_size({'width':width,'height':height});page.wait_for_timeout(450)
        check(f'navigation stays inside {width}x{height}',page.locator('#nav').evaluate('(n)=>{const r=n.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&r.bottom<=innerHeight}'))
        check(f'no horizontal page overflow {width}x{height}',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
        if width>height:
            page.evaluate('document.getElementById("homeChip").click()');page.wait_for_selector('#l31Quick');page.wait_for_timeout(450)
            check('quick card stays above navigation in landscape',page.evaluate("document.getElementById('l31Quick').getBoundingClientRect().bottom<=document.getElementById('nav').getBoundingClientRect().top+1"))
            page.evaluate('OCApp.back()');page.wait_for_selector('#l31Quick',state='detached')
    page.evaluate("Cfg.v.envMode='manual';Cfg.v.envTime='late';Cfg.v.envWx='sunny';Object.assign(Env.s,{h:23.3},WX.sunny);Env.target();Env.dirty=1")
    page.wait_for_function("/rgb\\(18,\\s*19,\\s*47\\)/.test(getComputedStyle(document.querySelector('#sceneBackplate>.screen-sky')).backgroundImage)")
    check('night palette reaches the visible sky surface',True)
    check('night stars are actually visible',page.evaluate("Home.stage.root.dataset.st==='1'&&getComputedStyle(Home.stage.root.querySelector('.stars')).display!=='none'"))
    # Full restore keeps extended life progress and rewrites character references.
    page.set_viewport_size({'width':412,'height':892})
    page.evaluate('''()=>{const c=Chars.list.find(c=>c.kind==='custom');IsleLife.X(c.id).sk={read:123};Cfg.v.l29.opt.suggest=false;L30.G()._regression={by:c.id};Cfg.v.l32={props:{bench:{x:.55,y:.9}}};OCPolish.setQuality('battery');Cfg.flush()}''')
    page.evaluate('Backup.exportAll()');page.locator('#bkSave').click()
    restore=page.evaluate('__nativeCalls.filter(c=>c.method==="saveFile").at(-1).text')
    page.evaluate('while(Ov.stack.length)Ov.back();Cfg.v.l29.opt.suggest=true;OCPolish.setQuality("full")')
    page.evaluate('(text)=>{window.__restoreResult=null;Backup.importFile(new File([text],"restore.ocbackup.json",{type:"application/json"})).then(ok=>window.__restoreResult=ok)}',restore)
    page.locator('#bkRestoreAll').click()
    page.wait_for_function('__restoreResult===true')
    with page.expect_navigation(wait_until='load'):
        page.wait_for_timeout(1100)
    page.wait_for_function('window.OCPolish?.ready && !document.getElementById("splash")')
    check('full restore keeps the graphics preference',page.evaluate('document.documentElement.dataset.quality==="battery"'))
    check('full restore keeps personality options',page.evaluate('Cfg.v.l29.opt.suggest===false'))
    check('full restore keeps skills and remaps character references',page.evaluate('''()=>{const id=Cfg.v.l30._regression.by;return !!Chars.byId(id)&&IsleLife.X(id).sk.read===123}'''))
    check('full restore applies island prop positions',page.evaluate('Math.abs(IsleWorld.props.bench.x-.55)<.001&&Math.abs(IsleWorld.props.bench.y-.9)<.001'))
    check('no JavaScript runtime errors',not errors)
    browser.close()
server.shutdown()
(ROOT/'docs/web-regression-results.json').write_text(json.dumps({'checks':checks,'errors':errors},ensure_ascii=False,indent=2))
print(f'{len(checks)} integration checks passed.')
