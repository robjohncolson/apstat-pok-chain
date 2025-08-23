#!/usr/bin/env node
/**
 * Bundle validation script for APStat PoK Chain Phase 8
 * Validates production bundle meets architecture requirements.
 */

const fs = require('fs');
const path = require('path');

// Phase 8 validation thresholds
const MAX_BUNDLE_SIZE = 5 * 1024 * 1024; // 5MB
const MAX_MAIN_JS_SIZE = 3 * 1024 * 1024; // 3MB for main.js
const MIN_COMPRESSION_RATIO = 0.7; // Expect at least 30% compression
const REQUIRED_FILES = [
    'public/index.html',
    'public/js/main.js',
    'public/css/styles.css'
];

function getFileSize(filePath) {
    try {
        const stats = fs.statSync(filePath);
        return stats.size;
    } catch (error) {
        return 0;
    }
}

function validateFileExists(filePath) {
    return fs.existsSync(filePath);
}

function calculateBundleSize(publicDir) {
    let totalSize = 0;
    
    function addDirectorySize(dir) {
        const items = fs.readdirSync(dir);
        
        for (const item of items) {
            const itemPath = path.join(dir, item);
            const stats = fs.statSync(itemPath);
            
            if (stats.isDirectory()) {
                addDirectorySize(itemPath);
            } else {
                totalSize += stats.size;
            }
        }
    }
    
    addDirectorySize(publicDir);
    return totalSize;
}

function validateMainJS(mainJsPath) {
    const content = fs.readFileSync(mainJsPath, 'utf8');
    const checks = {
        hasGoogClosure: content.includes('goog.') || content.includes('cljs.'),
        hasMinification: !content.includes('\n  ') && !content.includes('    '),
        hasTreeShaking: !content.includes('UNUSED') && !content.includes('function(){return}'),
        sizeLimitMet: content.length < MAX_MAIN_JS_SIZE,
        hasCompression: content.length < 1024 * 1024 // Less than 1MB indicates good compression
    };
    
    return checks;
}

function validateIndexHTML(indexPath) {
    const content = fs.readFileSync(indexPath, 'utf8');
    const checks = {
        hasMainJS: content.includes('main.js'),
        hasCSS: content.includes('styles.css'),
        hasMetaViewport: content.includes('viewport'),
        hasTitle: content.includes('<title>'),
        isMinimal: content.length < 5000 // Should be under 5KB
    };
    
    return checks;
}

function validateEDNStructure() {
    const ednDir = 'public/resources/edn';
    if (!fs.existsSync(ednDir)) {
        return { exists: false, count: 0, totalSize: 0 };
    }
    
    const ednFiles = fs.readdirSync(ednDir).filter(f => f.endsWith('.edn'));
    let totalSize = 0;
    
    for (const file of ednFiles) {
        totalSize += getFileSize(path.join(ednDir, file));
    }
    
    return {
        exists: true,
        count: ednFiles.length,
        totalSize,
        averageSize: totalSize / ednFiles.length
    };
}

function runValidation() {
    console.log('🔍 Phase 8 Bundle Validation\n');
    
    const results = {
        passed: 0,
        failed: 0,
        warnings: 0
    };
    
    // 1. File existence validation
    console.log('📁 Required Files:');
    for (const file of REQUIRED_FILES) {
        const exists = validateFileExists(file);
        if (exists) {
            console.log(`  ✅ ${file} (${Math.round(getFileSize(file) / 1024)}KB)`);
            results.passed++;
        } else {
            console.log(`  ❌ ${file} - MISSING`);
            results.failed++;
        }
    }
    
    // 2. Bundle size validation
    console.log('\n📦 Bundle Size Analysis:');
    const bundleSize = calculateBundleSize('public');
    const bundleMB = (bundleSize / (1024 * 1024)).toFixed(2);
    
    if (bundleSize <= MAX_BUNDLE_SIZE) {
        console.log(`  ✅ Total bundle: ${bundleMB}MB (under ${MAX_BUNDLE_SIZE / (1024 * 1024)}MB limit)`);
        results.passed++;
    } else {
        console.log(`  ❌ Total bundle: ${bundleMB}MB (exceeds ${MAX_BUNDLE_SIZE / (1024 * 1024)}MB limit)`);
        results.failed++;
    }
    
    // 3. Main JS validation
    const mainJsPath = 'public/js/main.js';
    if (validateFileExists(mainJsPath)) {
        console.log('\n⚡ Main JS Analysis:');
        const mainJSChecks = validateMainJS(mainJsPath);
        const mainJSSize = (getFileSize(mainJsPath) / (1024 * 1024)).toFixed(2);
        
        console.log(`  📊 Size: ${mainJSSize}MB`);
        
        Object.entries(mainJSChecks).forEach(([check, passed]) => {
            const status = passed ? '✅' : '❌';
            const checkName = check.replace(/([A-Z])/g, ' $1').toLowerCase();
            console.log(`  ${status} ${checkName}`);
            
            if (passed) results.passed++;
            else results.failed++;
        });
    }
    
    // 4. HTML validation
    const indexPath = 'public/index.html';
    if (validateFileExists(indexPath)) {
        console.log('\n🌐 HTML Analysis:');
        const htmlChecks = validateIndexHTML(indexPath);
        
        Object.entries(htmlChecks).forEach(([check, passed]) => {
            const status = passed ? '✅' : '❌';
            const checkName = check.replace(/([A-Z])/g, ' $1').toLowerCase();
            console.log(`  ${status} ${checkName}`);
            
            if (passed) results.passed++;
            else results.failed++;
        });
    }
    
    // 5. EDN curriculum validation
    console.log('\n📚 Curriculum EDN Analysis:');
    const ednValidation = validateEDNStructure();
    
    if (ednValidation.exists) {
        console.log(`  ✅ Found ${ednValidation.count} EDN files`);
        console.log(`  📊 Total curriculum: ${Math.round(ednValidation.totalSize / 1024)}KB`);
        console.log(`  📊 Average lesson: ${Math.round(ednValidation.averageSize / 1024)}KB`);
        
        if (ednValidation.count >= 77) {
            console.log(`  ✅ Complete curriculum (${ednValidation.count} lessons)`);
            results.passed++;
        } else {
            console.log(`  ⚠️  Incomplete curriculum (${ednValidation.count}/77 lessons)`);
            results.warnings++;
        }
        
        if (ednValidation.averageSize <= 8192) { // 8KB average
            console.log(`  ✅ Optimal lesson size (avg ${Math.round(ednValidation.averageSize / 1024)}KB)`);
            results.passed++;
        } else {
            console.log(`  ⚠️  Large lesson files (avg ${Math.round(ednValidation.averageSize / 1024)}KB)`);
            results.warnings++;
        }
    } else {
        console.log(`  ❌ EDN curriculum directory not found`);
        results.failed++;
    }
    
    // 6. Production readiness summary
    console.log('\n🎯 Production Readiness Summary:');
    console.log(`  ✅ Passed: ${results.passed}`);
    console.log(`  ❌ Failed: ${results.failed}`);
    console.log(`  ⚠️  Warnings: ${results.warnings}`);
    
    const totalChecks = results.passed + results.failed;
    const passRate = ((results.passed / totalChecks) * 100).toFixed(1);
    console.log(`  📊 Pass Rate: ${passRate}%`);
    
    if (results.failed === 0) {
        console.log('\n🚀 BUNDLE READY FOR DEPLOYMENT!');
        console.log('   All critical requirements met for Phase 8 release.');
        return 0;
    } else {
        console.log('\n❌ DEPLOYMENT BLOCKED');
        console.log(`   ${results.failed} critical issues must be resolved.`);
        return 1;
    }
}

// Run validation if called directly
if (require.main === module) {
    process.exit(runValidation());
}

module.exports = { runValidation };
