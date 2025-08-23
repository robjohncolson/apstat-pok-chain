#!/usr/bin/env python3
"""
Teacher QR Generation Script for APStat PoK Chain
Phase 8 deployment tool for generating QR codes from delta payloads.

Converts Transit/gzip delta chunks to PNG/GIF sequences for classroom distribution.
Supports chunking for large deltas, compression optimization, and batch processing.

Usage:
    python teacher_qr_gen.py --input delta.json --output qr_sequence --format png
    python teacher_qr_gen.py --batch deltas/ --output batch_qr/ --chunk-size 2048
"""

import argparse
import json
import gzip
import base64
import sys
import os
from pathlib import Path
from typing import List, Dict, Any, Tuple, Optional
import hashlib
import time

try:
    import qrcode
    from qrcode.image.styledpil import StyledPilImage
    from qrcode.image.styles.moduledrawers import RoundedModuleDrawer, SquareModuleDrawer
    from PIL import Image, ImageDraw, ImageFont
except ImportError as e:
    print(f"Error: Required libraries not installed. Run: pip install qrcode[pil] pillow")
    sys.exit(1)

# Phase 8 configuration constants
DEFAULT_CHUNK_SIZE = 2048  # Bytes per QR code (below 3KB limit)
QR_VERSION = 10  # QR code version for capacity
QR_ERROR_CORRECTION = qrcode.constants.ERROR_CORRECT_M  # Medium error correction
QR_BORDER = 4  # Border size
QR_BOX_SIZE = 10  # Pixel size per module

# Visual styling
QR_FILL_COLOR = "#1a1a2e"  # Dark blue
QR_BACK_COLOR = "#16213e"  # Darker blue background
HEADER_COLOR = "#0f3460"   # Header background
TEXT_COLOR = "#e94560"     # Accent red for text

class DeltaQRGenerator:
    """Generates QR code sequences from blockchain delta payloads."""
    
    def __init__(self, chunk_size: int = DEFAULT_CHUNK_SIZE, output_format: str = "png"):
        self.chunk_size = chunk_size
        self.output_format = output_format.lower()
        self.generated_files = []
        
        if self.output_format not in ["png", "gif"]:
            raise ValueError("Output format must be 'png' or 'gif'")
    
    def compress_delta(self, delta_data: Dict[str, Any]) -> bytes:
        """Compresses delta payload using gzip for size optimization."""
        try:
            # Convert to JSON and compress
            json_str = json.dumps(delta_data, separators=(',', ':'))
            json_bytes = json_str.encode('utf-8')
            
            # Apply gzip compression
            compressed = gzip.compress(json_bytes, compresslevel=9)
            
            # Base64 encode for QR compatibility
            encoded = base64.b64encode(compressed)
            
            print(f"Compression: {len(json_bytes)} -> {len(compressed)} -> {len(encoded)} bytes")
            return encoded
            
        except Exception as e:
            raise RuntimeError(f"Delta compression failed: {e}")
    
    def chunk_payload(self, payload: bytes) -> List[Tuple[int, bytes, str]]:
        """Splits payload into QR-sized chunks with metadata."""
        chunks = []
        total_chunks = (len(payload) + self.chunk_size - 1) // self.chunk_size
        
        for i in range(total_chunks):
            start_idx = i * self.chunk_size
            end_idx = min(start_idx + self.chunk_size, len(payload))
            chunk_data = payload[start_idx:end_idx]
            
            # Create chunk with metadata
            chunk_id = f"{i+1:03d}"
            chunk_hash = hashlib.sha256(chunk_data).hexdigest()[:8]
            
            chunks.append((i, chunk_data, chunk_hash))
        
        return chunks
    
    def create_chunk_metadata(self, chunks: List[Tuple[int, bytes, str]], 
                            delta_hash: str) -> Dict[str, Any]:
        """Creates metadata for chunk reconstruction."""
        return {
            "version": "1.0",
            "total_chunks": len(chunks),
            "delta_hash": delta_hash,
            "chunk_hashes": [chunk[2] for chunk in chunks],
            "generated_at": int(time.time()),
            "chunk_size": self.chunk_size
        }
    
    def create_qr_code(self, data: bytes, chunk_num: int = 0, 
                      total_chunks: int = 1, metadata: Optional[str] = None) -> Image.Image:
        """Creates styled QR code with educational branding."""
        
        # Prepare QR code
        qr = qrcode.QRCode(
            version=QR_VERSION,
            error_correction=QR_ERROR_CORRECTION,
            box_size=QR_BOX_SIZE,
            border=QR_BORDER,
        )
        
        # Add data to QR code
        qr.add_data(data)
        qr.make(fit=True)
        
        # Create styled image
        img = qr.make_image(
            image_factory=StyledPilImage,
            module_drawer=RoundedModuleDrawer(),
            fill_color=QR_FILL_COLOR,
            back_color=QR_BACK_COLOR
        )
        
        # Add header with metadata
        header_height = 80
        total_width = img.size[0]
        total_height = img.size[1] + header_height
        
        # Create final image with header
        final_img = Image.new('RGB', (total_width, total_height), HEADER_COLOR)
        final_img.paste(img, (0, header_height))
        
        # Add text header
        draw = ImageDraw.Draw(final_img)
        
        try:
            # Try to load a good font
            font_title = ImageFont.truetype("arial.ttf", 16)
            font_meta = ImageFont.truetype("arial.ttf", 12)
        except:
            # Fallback to default font
            font_title = ImageFont.load_default()
            font_meta = ImageFont.load_default()
        
        # Title
        title = f"APStat PoK Chain - Delta {chunk_num}/{total_chunks}"
        title_bbox = draw.textbbox((0, 0), title, font=font_title)
        title_x = (total_width - (title_bbox[2] - title_bbox[0])) // 2
        draw.text((title_x, 10), title, fill=TEXT_COLOR, font=font_title)
        
        # Metadata
        if metadata:
            meta_bbox = draw.textbbox((0, 0), metadata, font=font_meta)
            meta_x = (total_width - (meta_bbox[2] - meta_bbox[0])) // 2
            draw.text((meta_x, 35), metadata, fill="#ffffff", font=font_meta)
        
        # Instructions
        instructions = "Scan with APStat PoK app to sync blockchain"
        inst_bbox = draw.textbbox((0, 0), instructions, font=font_meta)
        inst_x = (total_width - (inst_bbox[2] - inst_bbox[0])) // 2
        draw.text((inst_x, 55), instructions, fill="#cccccc", font=font_meta)
        
        return final_img
    
    def generate_qr_sequence(self, delta_data: Dict[str, Any], 
                           output_path: Path, filename_prefix: str = "delta") -> List[Path]:
        """Generates complete QR sequence from delta data."""
        
        # Step 1: Compress delta
        compressed_payload = self.compress_delta(delta_data)
        delta_hash = hashlib.sha256(compressed_payload).hexdigest()[:16]
        
        # Step 2: Check if chunking needed
        if len(compressed_payload) <= self.chunk_size:
            # Single QR code
            metadata_str = f"Hash: {delta_hash}"
            qr_img = self.create_qr_code(compressed_payload, 1, 1, metadata_str)
            
            output_file = output_path / f"{filename_prefix}_{delta_hash}.{self.output_format}"
            qr_img.save(output_file)
            self.generated_files.append(output_file)
            
            print(f"Generated single QR: {output_file}")
            return [output_file]
        
        # Step 3: Multiple QR codes needed
        chunks = self.chunk_payload(compressed_payload)
        metadata = self.create_chunk_metadata(chunks, delta_hash)
        
        # Generate metadata QR first
        metadata_json = json.dumps(metadata, separators=(',', ':')).encode('utf-8')
        metadata_qr = self.create_qr_code(
            metadata_json, 0, len(chunks) + 1, 
            f"Metadata - {len(chunks)} chunks"
        )
        
        metadata_file = output_path / f"{filename_prefix}_{delta_hash}_meta.{self.output_format}"
        metadata_qr.save(metadata_file)
        self.generated_files.append(metadata_file)
        
        # Generate chunk QRs
        generated_files = [metadata_file]
        
        for chunk_idx, chunk_data, chunk_hash in chunks:
            chunk_metadata = f"Chunk {chunk_idx + 1}/{len(chunks)} - {chunk_hash}"
            
            chunk_qr = self.create_qr_code(
                chunk_data, chunk_idx + 1, len(chunks), chunk_metadata
            )
            
            chunk_file = output_path / f"{filename_prefix}_{delta_hash}_chunk_{chunk_idx+1:03d}.{self.output_format}"
            chunk_qr.save(chunk_file)
            generated_files.append(chunk_file)
            self.generated_files.append(chunk_file)
        
        print(f"Generated {len(generated_files)} QR codes for delta {delta_hash}")
        return generated_files
    
    def generate_animated_gif(self, qr_files: List[Path], output_path: Path) -> Path:
        """Creates animated GIF from QR sequence for easy classroom display."""
        if self.output_format != "gif":
            print("Animated GIF creation requires gif output format")
            return None
        
        if len(qr_files) < 2:
            print("Animation requires multiple QR codes")
            return qr_files[0] if qr_files else None
        
        # Load all images
        images = []
        for qr_file in qr_files:
            img = Image.open(qr_file)
            images.append(img)
        
        # Create animated GIF
        gif_path = output_path / f"animated_{qr_files[0].stem.split('_')[1]}.gif"
        images[0].save(
            gif_path,
            save_all=True,
            append_images=images[1:],
            duration=3000,  # 3 seconds per frame
            loop=0  # Infinite loop
        )
        
        print(f"Generated animated GIF: {gif_path}")
        return gif_path


def load_delta_file(file_path: Path) -> Dict[str, Any]:
    """Loads delta data from JSON file."""
    try:
        with open(file_path, 'r') as f:
            return json.load(f)
    except Exception as e:
        raise RuntimeError(f"Failed to load delta file {file_path}: {e}")


def main():
    """Main CLI interface for teacher QR generation."""
    parser = argparse.ArgumentParser(
        description="Generate QR codes from APStat PoK blockchain deltas",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Single delta file
  python teacher_qr_gen.py --input lesson_delta.json --output qr_codes/ --format png
  
  # Batch processing
  python teacher_qr_gen.py --batch deltas/ --output batch_qr/ --chunk-size 2048
  
  # Animated GIF for classroom display
  python teacher_qr_gen.py --input big_delta.json --output gifs/ --format gif --animate
        """
    )
    
    # Input options
    input_group = parser.add_mutually_exclusive_group(required=True)
    input_group.add_argument('--input', '-i', type=Path, 
                           help='Single delta JSON file to process')
    input_group.add_argument('--batch', '-b', type=Path,
                           help='Directory containing delta JSON files')
    
    # Output options
    parser.add_argument('--output', '-o', type=Path, required=True,
                       help='Output directory for QR codes')
    parser.add_argument('--format', '-f', choices=['png', 'gif'], default='png',
                       help='Output format (default: png)')
    parser.add_argument('--chunk-size', '-c', type=int, default=DEFAULT_CHUNK_SIZE,
                       help=f'Chunk size in bytes (default: {DEFAULT_CHUNK_SIZE})')
    parser.add_argument('--animate', '-a', action='store_true',
                       help='Create animated GIF for multi-chunk sequences')
    
    # Processing options
    parser.add_argument('--prefix', '-p', default='delta',
                       help='Filename prefix for generated QR codes')
    parser.add_argument('--verbose', '-v', action='store_true',
                       help='Verbose output')
    
    args = parser.parse_args()
    
    # Validate arguments
    if args.chunk_size > 3000:  # QR code capacity limit
        print("Warning: Chunk size > 3KB may exceed QR code capacity")
    
    # Create output directory
    args.output.mkdir(parents=True, exist_ok=True)
    
    # Initialize generator
    generator = DeltaQRGenerator(args.chunk_size, args.format)
    
    try:
        if args.input:
            # Single file processing
            delta_data = load_delta_file(args.input)
            qr_files = generator.generate_qr_sequence(
                delta_data, args.output, args.prefix
            )
            
            if args.animate and args.format == "gif" and len(qr_files) > 1:
                generator.generate_animated_gif(qr_files, args.output)
        
        elif args.batch:
            # Batch processing
            delta_files = list(args.batch.glob("*.json"))
            if not delta_files:
                print(f"No JSON files found in {args.batch}")
                return
            
            print(f"Processing {len(delta_files)} delta files...")
            
            for delta_file in delta_files:
                if args.verbose:
                    print(f"Processing {delta_file.name}...")
                
                delta_data = load_delta_file(delta_file)
                file_prefix = f"{args.prefix}_{delta_file.stem}"
                
                qr_files = generator.generate_qr_sequence(
                    delta_data, args.output, file_prefix
                )
                
                if args.animate and args.format == "gif" and len(qr_files) > 1:
                    generator.generate_animated_gif(qr_files, args.output)
        
        print(f"\nGenerated {len(generator.generated_files)} QR code files in {args.output}")
        print("Files are ready for classroom distribution!")
        
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
