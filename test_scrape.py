from playwright.sync_api import sync_playwright

def run(playwright):
    browser = playwright.chromium.launch()
    page = browser.new_page()
    
    # Listen to all network requests
    def handle_response(response):
        if "json" in response.headers.get("content-type", "") or "api" in response.url:
            print(f"API Call: {response.url}")
            try:
                print(response.json()[:200]) # Print start of JSON
            except:
                pass

    page.on("response", handle_response)
    
    print("Navigating to https://streamed.pk")
    page.goto("https://streamed.pk", wait_until="networkidle")
    
    print("Navigating to match page...")
    # Click the first match link
    cards = page.locator("div.h-full.mt-2.p-1 > div > div > div > div > a")
    if cards.count() > 16:
        href = cards.nth(16).get_attribute("href")
        print(f"Going to match: {href}")
        page.goto("https://streamed.pk" + href, wait_until="networkidle")
        
    browser.close()

with sync_playwright() as playwright:
    run(playwright)
