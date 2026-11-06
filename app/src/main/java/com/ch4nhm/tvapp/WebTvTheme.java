package com.ch4nhm.tvapp;

import android.webkit.WebView;

import org.json.JSONObject;

import java.util.Locale;

/** Applies the app's tvOS-inspired visual language to TVBox's browser-based config center. */
final class WebTvTheme {

    private static final String CSS = """
            :root {
              color-scheme: dark;
              --tv-bg: #050506;
              --tv-surface: rgba(28, 28, 30, .76);
              --tv-surface-strong: rgba(44, 44, 46, .92);
              --tv-line: rgba(255, 255, 255, .10);
              --tv-line-strong: rgba(255, 255, 255, .24);
              --tv-text: #f5f5f7;
              --tv-secondary: #a1a1a6;
              --tv-muted: #6e6e73;
              --tv-accent: __ACCENT__;
              --tv-on-accent: __ON_ACCENT__;
            }

            html, body {
              min-height: 100% !important;
              margin: 0 !important;
              background: var(--tv-bg) !important;
              color: var(--tv-text) !important;
              font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display",
                "SF Pro Text", "Helvetica Neue", "PingFang SC", sans-serif !important;
              -webkit-font-smoothing: antialiased !important;
              text-rendering: optimizeLegibility !important;
            }

            body {
              padding: 28px 34px 92px !important;
              overflow-x: hidden !important;
              background:
                radial-gradient(900px 520px at 76% -12%, rgba(255, 255, 255, .085), transparent 62%),
                radial-gradient(680px 460px at 8% 28%, rgba(255, 255, 255, .025), transparent 68%),
                linear-gradient(180deg, #09090a 0, #050506 42%, #030304 100%) !important;
              background-attachment: fixed !important;
            }

            body::before {
              content: "";
              position: fixed;
              inset: 0;
              pointer-events: none;
              background:
                linear-gradient(90deg, transparent 0, rgba(255,255,255,.018) 50%, transparent 100%),
                radial-gradient(circle at 50% 120%, rgba(255,255,255,.05), transparent 52%);
            }

            * {
              box-sizing: border-box !important;
              text-shadow: none !important;
            }

            ::-webkit-scrollbar { width: 0 !important; height: 0 !important; }
            ::selection { color: #050506 !important; background: var(--tv-text) !important; }

            #host {
              min-height: 48px !important;
              padding: 11px 20px !important;
              border: 1px solid var(--tv-line) !important;
              border-radius: 999px !important;
              background: rgba(255, 255, 255, .065) !important;
              color: #d7d7dc !important;
              font-size: 14px !important;
              font-weight: 600 !important;
              letter-spacing: .015em !important;
              box-shadow: inset 0 1px 0 rgba(255, 255, 255, .06) !important;
              backdrop-filter: blur(24px) saturate(130%) !important;
            }

            #categoryTabs {
              min-height: 72px !important;
              padding: 9px !important;
              border: 1px solid var(--tv-line) !important;
              border-radius: 22px !important;
              background: rgba(24, 24, 26, .72) !important;
              box-shadow: 0 18px 50px rgba(0, 0, 0, .34),
                inset 0 1px 0 rgba(255, 255, 255, .055) !important;
              backdrop-filter: blur(34px) saturate(135%) !important;
              overflow-x: auto !important;
              display: flex !important;
              flex-wrap: nowrap !important;
              align-items: center !important;
              gap: 3px !important;
            }

            #categoryTabs > * {
              flex: 0 0 auto !important;
              min-height: 48px !important;
              padding: 8px 13px !important;
              border: 1px solid transparent !important;
              border-radius: 15px !important;
              background: transparent !important;
              color: var(--tv-secondary) !important;
              font-family: inherit !important;
              font-size: 16px !important;
              font-weight: 600 !important;
              letter-spacing: -.01em !important;
              box-shadow: none !important;
              transition: color .16s ease, background .16s ease,
                transform .16s ease, box-shadow .16s ease !important;
            }

            #categoryTabs > .active,
            #categoryTabs > [aria-selected="true"],
            #categoryTabs > [data-active="true"] {
              background: var(--tv-text) !important;
              color: #050506 !important;
              box-shadow: 0 8px 24px rgba(0, 0, 0, .42) !important;
            }

            #sectionTitle,
            #detailTitle {
              color: var(--tv-text) !important;
              font-family: inherit !important;
              font-weight: 700 !important;
              letter-spacing: -.035em !important;
            }

            #sectionTitle { font-size: 38px !important; line-height: 1.08 !important; }
            #sectionHint { color: var(--tv-secondary) !important; font-size: 15px !important; }
            #detailTitle { font-size: 28px !important; line-height: 1.16 !important; }

            #cards {
              gap: 18px !important;
            }

            div:has(> #sectionTitle) {
              border-color: transparent !important;
              background: transparent !important;
              box-shadow: none !important;
            }

            div:has(> #detailTitle) {
              border: 1px solid var(--tv-line) !important;
              border-radius: 24px !important;
              background:
                linear-gradient(145deg, rgba(255,255,255,.07), rgba(255,255,255,.025)),
                rgba(24, 24, 26, .76) !important;
              box-shadow: 0 18px 44px rgba(0, 0, 0, .28),
                inset 0 1px 0 rgba(255,255,255,.05) !important;
              backdrop-filter: blur(28px) saturate(130%) !important;
            }

            #cards > * {
              min-width: 0 !important;
              padding: 22px !important;
              border: 1px solid var(--tv-line) !important;
              border-radius: 24px !important;
              background:
                linear-gradient(145deg, rgba(255,255,255,.075), rgba(255,255,255,.025)),
                var(--tv-surface) !important;
              box-shadow: 0 18px 44px rgba(0, 0, 0, .28),
                inset 0 1px 0 rgba(255, 255, 255, .045) !important;
              backdrop-filter: blur(28px) saturate(130%) !important;
              transition: transform .18s ease, border-color .18s ease,
                background .18s ease, box-shadow .18s ease !important;
            }

            #cards > .active,
            #cards > [aria-selected="true"],
            #cards > [data-active="true"] {
              border-color: var(--tv-line-strong) !important;
              background:
                linear-gradient(145deg, rgba(255,255,255,.13), rgba(255,255,255,.04)),
                var(--tv-surface-strong) !important;
              box-shadow: 0 20px 54px rgba(0, 0, 0, .42),
                inset 0 1px 0 rgba(255, 255, 255, .08) !important;
            }

            #msg, #detailHint {
              color: var(--tv-secondary) !important;
              font-size: 15px !important;
              line-height: 1.55 !important;
            }

            input, textarea, select,
            #cookie,
            [contenteditable="true"] {
              min-height: 52px !important;
              padding: 13px 16px !important;
              border: 1px solid var(--tv-line) !important;
              border-radius: 15px !important;
              outline: none !important;
              background: rgba(118, 118, 128, .16) !important;
              color: var(--tv-text) !important;
              caret-color: var(--tv-accent) !important;
              font-family: inherit !important;
              font-size: 16px !important;
              box-shadow: inset 0 1px 0 rgba(255, 255, 255, .035) !important;
            }

            input::placeholder, textarea::placeholder { color: var(--tv-muted) !important; }

            button, [role="button"], #detailActions > * {
              min-height: 48px !important;
              padding: 11px 20px !important;
              border: 1px solid var(--tv-line) !important;
              border-radius: 999px !important;
              background: rgba(255, 255, 255, .11) !important;
              color: var(--tv-text) !important;
              font-family: inherit !important;
              font-size: 15px !important;
              font-weight: 600 !important;
              letter-spacing: -.005em !important;
              box-shadow: inset 0 1px 0 rgba(255, 255, 255, .06) !important;
              transition: transform .16s ease, background .16s ease,
                border-color .16s ease !important;
            }

            #detailActions > *:first-child {
              border-color: transparent !important;
              background: var(--tv-accent) !important;
              color: var(--tv-on-accent) !important;
            }

            #qrBox {
              width: fit-content !important;
              min-width: 0 !important;
              padding: 18px !important;
              border-radius: 24px !important;
              background: #fff !important;
              color: #050506 !important;
              box-shadow: 0 20px 56px rgba(0, 0, 0, .48) !important;
            }

            #qr {
              display: block !important;
              width: 220px !important;
              height: 220px !important;
              max-width: min(220px, 38vw) !important;
              max-height: min(220px, 38vw) !important;
              object-fit: contain !important;
              border-radius: 8px !important;
              background: #fff !important;
            }

            #toast {
              padding: 12px 20px !important;
              border: 1px solid var(--tv-line) !important;
              border-radius: 999px !important;
              background: rgba(28, 28, 30, .92) !important;
              color: var(--tv-text) !important;
              box-shadow: 0 12px 36px rgba(0, 0, 0, .46) !important;
              backdrop-filter: blur(28px) !important;
            }

            .wrap {
              max-width: 1480px !important;
              padding: 12px 4px 0 !important;
            }

            .hero {
              align-items: center !important;
              margin-bottom: 20px !important;
            }

            .title {
              font-size: 34px !important;
              font-weight: 750 !important;
              letter-spacing: -.045em !important;
              line-height: 1.06 !important;
            }

            .sub {
              margin-top: 9px !important;
              color: var(--tv-secondary) !important;
              font-size: 15px !important;
              letter-spacing: -.01em !important;
            }

            .panel {
              padding: 22px !important;
              border: 1px solid var(--tv-line) !important;
              border-radius: 28px !important;
              background:
                linear-gradient(145deg, rgba(255,255,255,.045), rgba(255,255,255,.015)),
                rgba(18, 18, 20, .62) !important;
              box-shadow: 0 24px 70px rgba(0, 0, 0, .34),
                inset 0 1px 0 rgba(255, 255, 255, .045) !important;
              backdrop-filter: blur(32px) saturate(125%) !important;
            }

            .section-head {
              margin-bottom: 18px !important;
            }

            .layout {
              grid-template-columns: minmax(0, 1fr) 380px !important;
              gap: 20px !important;
              align-items: start !important;
            }

            .cards {
              grid-template-columns: repeat(2, minmax(0, 1fr)) !important;
              gap: 16px !important;
              align-content: start !important;
            }

            .card {
              min-height: 112px !important;
              padding: 18px !important;
              border: 1px solid var(--tv-line) !important;
              border-radius: 20px !important;
              background:
                linear-gradient(145deg, rgba(255,255,255,.07), rgba(255,255,255,.02)),
                rgba(30, 30, 32, .72) !important;
              box-shadow: inset 0 1px 0 rgba(255, 255, 255, .045) !important;
              transition: transform .16s ease, background .16s ease,
                border-color .16s ease, box-shadow .16s ease !important;
              align-self: start !important;
            }

            .card:hover,
            .card:focus {
              border-color: var(--tv-line-strong) !important;
              background:
                linear-gradient(145deg, rgba(255,255,255,.13), rgba(255,255,255,.04)),
                rgba(44, 44, 46, .92) !important;
            }

            .name {
              color: var(--tv-text) !important;
              font-size: 18px !important;
              font-weight: 650 !important;
              letter-spacing: -.025em !important;
            }

            .meta,
            .empty,
            .hint {
              color: var(--tv-secondary) !important;
              font-size: 14px !important;
            }

            .badge {
              padding: 5px 10px !important;
              border: 1px solid var(--tv-line) !important;
              border-radius: 999px !important;
              background: rgba(255, 255, 255, .07) !important;
              color: #c7c7cc !important;
              font-size: 12px !important;
              font-weight: 600 !important;
            }

            .badge.ok {
              border-color: rgba(48, 209, 88, .28) !important;
              background: rgba(48, 209, 88, .11) !important;
              color: #85e89d !important;
            }

            .badge.bad {
              border-color: rgba(255, 69, 58, .28) !important;
              background: rgba(255, 69, 58, .11) !important;
              color: #ff8b84 !important;
            }

            .detail {
              padding: 20px !important;
              border: 1px solid var(--tv-line) !important;
              border-radius: 22px !important;
              background:
                linear-gradient(145deg, rgba(255,255,255,.07), rgba(255,255,255,.02)),
                rgba(28, 28, 30, .74) !important;
              box-shadow: 0 18px 44px rgba(0, 0, 0, .24),
                inset 0 1px 0 rgba(255, 255, 255, .045) !important;
              backdrop-filter: blur(28px) saturate(130%) !important;
            }

            .detail h3 {
              margin-bottom: 14px !important;
              color: var(--tv-text) !important;
              font-size: 27px !important;
              font-weight: 680 !important;
              letter-spacing: -.035em !important;
            }

            .status {
              padding: 13px 15px !important;
              border: 1px solid var(--tv-line) !important;
              border-radius: 15px !important;
              background: rgba(118, 118, 128, .14) !important;
              color: #d1d1d6 !important;
              font-size: 14px !important;
            }

            .field {
              margin-top: 16px !important;
            }

            .actions {
              gap: 10px !important;
            }

            .btn {
              border: 1px solid transparent !important;
              background: var(--tv-text) !important;
              color: var(--tv-on-accent) !important;
            }

            .btn.secondary {
              border-color: var(--tv-line) !important;
              background: rgba(255, 255, 255, .16) !important;
              color: var(--tv-text) !important;
            }

            .btn.ghost {
              border-color: var(--tv-line) !important;
              background: rgba(255, 255, 255, .07) !important;
              color: var(--tv-text) !important;
            }

            .btn.danger {
              border-color: rgba(255, 69, 58, .28) !important;
              background: rgba(255, 69, 58, .12) !important;
              color: #ff8b84 !important;
            }

            .tab:hover {
              border-color: var(--tv-line) !important;
              background: rgba(255, 255, 255, .07) !important;
            }

            .qrbox {
              margin-top: 16px !important;
            }

            .qrbox .btn {
              border-color: rgba(0, 0, 0, .12) !important;
              background: rgba(5, 5, 6, .82) !important;
              color: #fff !important;
            }

            a { color: var(--tv-text) !important; }

            #categoryTabs > *:focus,
            #cards > *:focus,
            button:focus,
            [role="button"]:focus,
            input:focus,
            textarea:focus,
            select:focus,
            #cookie:focus,
            a:focus {
              outline: 3px solid rgba(255, 255, 255, .96) !important;
              outline-offset: 3px !important;
              border-color: transparent !important;
              box-shadow: 0 0 0 7px rgba(255, 255, 255, .12),
                0 18px 48px rgba(0, 0, 0, .48) !important;
            }

            #categoryTabs > *:focus,
            button:focus,
            [role="button"]:focus {
              transform: scale(1.025) !important;
            }

            @keyframes tvReveal {
              from { opacity: 0; transform: translateY(12px); }
              to { opacity: 1; transform: translateY(0); }
            }

            #categoryTabs { animation: tvReveal .44s cubic-bezier(.2,.8,.2,1) both !important; }
            #sectionTitle { animation: tvReveal .52s .05s cubic-bezier(.2,.8,.2,1) both !important; }
            #cards, #detailTitle { animation: tvReveal .58s .10s cubic-bezier(.2,.8,.2,1) both !important; }

            @media (max-width: 900px) {
              body { padding: 20px 20px 86px !important; }
              #sectionTitle { font-size: 31px !important; }
              #cards { grid-template-columns: 1fr !important; }
            }
            """;

    private WebTvTheme() {
    }

    static void install(WebView view, int accent, int onAccent) {
        String accentColor = color(accent);
        String onAccentColor = color(onAccent);
        String css = CSS.replace("__ACCENT__", accentColor)
                .replace("__ON_ACCENT__", onAccentColor);
        String script = "(function(){"
                + "var id='tvbox-apple-theme';"
                + "var css=" + JSONObject.quote(css) + ";"
                + "var style=document.getElementById(id);"
                + "if(!style){style=document.createElement('style');style.id=id;"
                + "(document.head||document.documentElement).appendChild(style);}"
                + "if(style.textContent!==css)style.textContent=css;"
                + "document.documentElement.classList.add('tvbox-apple');"
                + "if(document.body)document.body.classList.add('tvbox-apple');"
                + installNavigation()
                + "})();";
        view.evaluateJavascript(script, null);
    }

    private static String installNavigation() {
        return """
                if(!window.__tvboxTvNavigation){
                  window.__tvboxTvNavigation=true;
                  var selector='button:not([disabled]),a[href],input:not([type=hidden]):not([disabled]),textarea:not([disabled]),select:not([disabled]),[role="button"],[tabindex="0"],.card';
                  function items(){
                    return Array.prototype.slice.call(document.querySelectorAll(selector)).filter(function(el){
                      var r=el.getBoundingClientRect(),s=getComputedStyle(el);
                      return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'&&!el.disabled;
                    });
                  }
                  function center(el){var r=el.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};}
                  function pick(current,candidates,dir){
                    var origin=center(current),best=null,bestScore=Infinity;
                    candidates.forEach(function(candidate){
                      if(candidate===current)return;
                      var p=center(candidate),dx=p.x-origin.x,dy=p.y-origin.y;
                      var main=dir[0]?dx*dir[0]:dy*dir[1];
                      if(main<=8)return;
                      var cross=Math.abs(dir[0]?dy:dx);
                      var score=main+cross*2.15;
                      if(score<bestScore){bestScore=score;best=candidate;}
                    });
                    return best;
                  }
                  function focusFirst(){
                    var list=items(),preferred=document.querySelector('#categoryTabs .active')||list[0];
                    if(preferred){preferred.focus({preventScroll:true});return true;}return false;
                  }
                  window.__tvboxTvFocusFirst=focusFirst;
                  document.addEventListener('keydown',function(event){
                    var key=event.key,dirs={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]};
                    if(event.key==='Enter'&&document.activeElement===document.body){if(focusFirst())event.preventDefault();return;}
                    var dir=dirs[key];if(!dir)return;
                    var list=items(),current=document.activeElement;
                    if(list.indexOf(current)<0){if(focusFirst())event.preventDefault();return;}
                    var tabs=current.closest&&current.closest('#categoryTabs');
                    var cards=current.closest&&current.closest('#cards');
                    var detail=current.closest&&current.closest('.detail');
                    var section=current.closest&&current.closest('.section-head');
                    if(tabs){
                      if(key==='ArrowDown'){
                        var headButton=document.querySelector('.section-head .btn');
                        if(headButton)headButton.focus({preventScroll:false});
                      }else{
                        var tabItems=list.filter(function(el){return tabs.contains(el);});
                        var nextTab=pick(current,tabItems,dir);
                        if(nextTab)nextTab.focus({preventScroll:false});
                      }
                      event.preventDefault();return;
                    }
                    if(section){
                      if(key==='ArrowDown'){
                        var firstCard=document.querySelector('#cards .card');
                        if(firstCard)firstCard.focus({preventScroll:false});
                      }else if(key==='ArrowUp'){
                        var activeTab=document.querySelector('#categoryTabs .active');
                        if(activeTab)activeTab.focus({preventScroll:false});
                      }
                      event.preventDefault();return;
                    }
                    if(cards){
                      var cardItems=list.filter(function(el){return cards.contains(el);});
                      var nextCard=pick(current,cardItems,dir);
                      if(nextCard){nextCard.focus({preventScroll:false});event.preventDefault();return;}
                      if(key==='ArrowRight'){
                        var detailBox=document.querySelector('.detail');
                        var firstDetail=detailBox&&list.filter(function(el){return detailBox.contains(el);})[0];
                        if(firstDetail)firstDetail.focus({preventScroll:false});
                      }else if(key==='ArrowUp'){
                        var tab=document.querySelector('#categoryTabs .active');
                        if(tab)tab.focus({preventScroll:false});
                      }
                      event.preventDefault();return;
                    }
                    if(detail){
                      var detailItems=list.filter(function(el){return detail.contains(el);});
                      if(key==='ArrowDown'&&current.id==='cookie'){
                        var firstAction=list.filter(function(el){
                          return el.closest&&el.closest('#detailActions')&&el.classList.contains('btn');
                        })[0];
                        if(firstAction)firstAction.focus({preventScroll:false});
                        event.preventDefault();return;
                      }
                      var nextDetail=pick(current,detailItems,dir);
                      if(nextDetail){nextDetail.focus({preventScroll:false});event.preventDefault();return;}
                      if(key==='ArrowLeft'){
                        var grid=document.querySelector('#cards');
                        var cardItems=list.filter(function(el){return grid&&grid.contains(el);});
                        var targetCard=pick(current,cardItems,dir);
                        if(targetCard)targetCard.focus({preventScroll:false});
                      }else if(key==='ArrowUp'){
                        var tab=document.querySelector('#categoryTabs .active');
                        if(tab)tab.focus({preventScroll:false});
                      }
                      event.preventDefault();return;
                    }
                    var best=pick(current,list,dir);
                    if(best){best.focus({preventScroll:false});event.preventDefault();}
                  },true);
                }
                setTimeout(function(){if(window.__tvboxTvFocusFirst)window.__tvboxTvFocusFirst();},180);
                """;
    }

    private static String color(int value) {
        return String.format(Locale.US, "#%06X", value & 0xFFFFFF);
    }
}
