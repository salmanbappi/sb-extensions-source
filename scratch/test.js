const puppeteer = require('puppeteer');

(async () => {
    const browser = await puppeteer.launch({
        args: ['--no-sandbox', '--disable-setuid-sandbox']
    });
    const page = await browser.newPage();
    
    // Set user agent
    await page.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');
    
    // Catch all network requests
    page.on('request', request => {
        const url = request.url();
        if (url.includes('googlevideo.com') || url.includes('youtube.com') || url.includes('.mp4') || url.includes('.m3u8')) {
            console.log('[FOUND MEDIA URL]', url);
        }
    });

    try {
        console.log('Navigating to blogger...');
        await page.goto('https://www.blogger.com/video.g?token=AD6v5dyi8VrNiPsGHSQbjrxp2H_Z6vzNSSGYRwhIujO9KHYWIWSi6tWzHzJd0os8WE0CdwoTEBzRBGkszK1tJJUSUETaCOC1aR-TWItfUAjg0OlhpAvLbnpSOYNyHBmZ5s4BPVMeX40&origin=huzianimeee.blogspot.com', { waitUntil: 'networkidle2' });
        
        console.log('Page loaded. Attempting to click play...');
        
        // Wait and click
        await page.evaluate(() => {
            return new Promise(resolve => {
                let attempts = 0;
                let interval = setInterval(() => {
                    try {
                        let v = document.querySelector('video');
                        if (v) { v.muted = true; v.play(); }
                        let btns = document.querySelectorAll('button, div[role="button"], .play-button, .vjs-big-play-button, #player, .ytp-large-play-button');
                        btns.forEach(b => { 
                            console.log('Clicking button:', b.className);
                            b.click(); 
                        });
                    } catch(e) { console.error(e); }
                    
                    attempts++;
                    if (attempts > 20) {
                        clearInterval(interval);
                        resolve();
                    }
                }, 500);
            });
        });
        
        console.log('Click loop finished. Waiting a bit for network requests...');
        await new Promise(r => setTimeout(r, 5000));
        
        console.log('DOM snapshot:', await page.content());

    } catch (e) {
        console.error(e);
    } finally {
        await browser.close();
    }
})();
