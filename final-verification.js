// Final Curriculum Loading Verification
// Run this in browser console after refreshing the page

console.log("=== Final Curriculum Loading Verification ===");

// Manual test first to confirm HTTP access
console.log("1. Testing direct HTTP access...");
fetch('/resources/edn/index.edn')
  .then(response => {
    console.log("✅ Index fetch status:", response.status);
    return response.text();
  })
  .then(text => {
    console.log("✅ Index content length:", text.length);
    console.log("✅ Index content preview:", text.substring(0, 150) + "...");
    
    // Test lesson file
    return fetch('/resources/edn/unit-1-lesson-2.edn');
  })
  .then(response => {
    console.log("✅ Lesson fetch status:", response.status);
    return response.text();
  })
  .then(text => {
    console.log("✅ Lesson content length:", text.length);
    console.log("✅ Lesson has questions:", text.includes(':questions'));
  })
  .catch(error => {
    console.error("❌ HTTP access test failed:", error);
  });

// Wait a bit then check app state
setTimeout(() => {
  console.log("\n2. Checking application state after reload...");
  
  try {
    // Check if Re-frame is available
    if (typeof window.re_frame !== 'undefined') {
      const db = window.re_frame.db.app_db.state;
      console.log("✅ Re-frame database accessible");
      console.log("📊 Database keys:", Object.keys(db));
      console.log("📚 Curriculum index exists:", !!db['curriculum-index']);
      console.log("⚠️  Loading state:", !!db.ui?.loading);
      console.log("❌ Error state:", db.ui?.error || "none");
      
      if (db['curriculum-index']) {
        const curriculum = db['curriculum-index'];
        console.log("🎯 Units loaded:", curriculum.units?.length || 0);
        if (curriculum.units && curriculum.units.length > 0) {
          console.log("📝 First unit:", curriculum.units[0]);
        }
      }
    } else {
      console.log("❌ Re-frame not available");
    }
    
    // Check pokCore
    if (typeof window.pokCore !== 'undefined') {
      console.log("✅ pokCore available with methods:", Object.keys(window.pokCore));
    } else {
      console.log("❌ pokCore not available");
    }
    
  } catch (e) {
    console.error("❌ State check failed:", e);
  }
  
  console.log("\n3. Manual curriculum reload test...");
  if (typeof window.re_frame !== 'undefined') {
    // Trigger manual curriculum reload
    window.re_frame.core.dispatch(['pok.state/load-curriculum-index']);
    console.log("🔄 Manual curriculum reload triggered");
    
    // Check again after a delay
    setTimeout(() => {
      try {
        const db = window.re_frame.db.app_db.state;
        console.log("📚 Post-reload curriculum:", !!db['curriculum-index']);
        console.log("❌ Post-reload error:", db.ui?.error || "none");
        
        if (db['curriculum-index']) {
          console.log("🎉 SUCCESS: Curriculum loaded successfully!");
          console.log("📊 Total units:", db['curriculum-index'].units?.length);
        } else {
          console.log("⚠️  Curriculum still not loaded after manual trigger");
        }
      } catch (e) {
        console.error("❌ Post-reload check failed:", e);
      }
    }, 3000);
  }
}, 2000);

console.log("🚀 Verification running... Results will appear shortly");
