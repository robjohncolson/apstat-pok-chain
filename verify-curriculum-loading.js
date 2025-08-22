// Curriculum Loading Verification Script
// Run this in the browser console after the app loads

console.log("=== Curriculum Loading Verification ===");

// 1. Check if the app initialized
setTimeout(() => {
    console.log("1. Checking app initialization...");
    console.log("pokCore available:", typeof window.pokCore !== 'undefined');
    
    // 2. Check Re-frame state
    console.log("\n2. Checking Re-frame state...");
    try {
        const appDb = window.re_frame.db.app_db.state;
        console.log("App database keys:", Object.keys(appDb));
        console.log("Curriculum index:", appDb['curriculum-index']);
        console.log("Loading state:", appDb.ui?.loading);
        console.log("Error state:", appDb.ui?.error);
    } catch (e) {
        console.error("Failed to access Re-frame state:", e);
    }
    
    // 3. Test manual curriculum loading
    console.log("\n3. Testing manual fetch of index.edn...");
    fetch('/resources/edn/index.edn')
        .then(response => {
            console.log("Fetch response status:", response.status);
            return response.text();
        })
        .then(text => {
            console.log("EDN content length:", text.length);
            console.log("EDN content preview:", text.substring(0, 200) + "...");
        })
        .catch(error => {
            console.error("Manual fetch failed:", error);
        });
        
    // 4. Test lesson file
    console.log("\n4. Testing lesson file fetch...");
    fetch('/resources/edn/unit-1-lesson-2.edn')
        .then(response => {
            console.log("Lesson file status:", response.status);
            return response.text();
        })
        .then(text => {
            console.log("Lesson content length:", text.length);
        })
        .catch(error => {
            console.error("Lesson fetch failed:", error);
        });
        
}, 3000);

console.log("Verification script loaded. Results will appear in 3 seconds...");
