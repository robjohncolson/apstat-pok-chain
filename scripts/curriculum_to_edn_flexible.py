#!/usr/bin/env python3
"""
Curriculum JSON to EDN Converter (Revised)
Converts large curriculum.json files to modular EDN format for ClojureScript apps.
Handles 816 questions across 9 units and 85 lessons with proper grouping and sorting.
"""

import json
import argparse
import os
from typing import Dict, List, Any, Tuple
from collections import defaultdict

def extract_unit_lesson_nums(question_id: str) -> Tuple[str, str]:
    """Extract unit and lesson numbers from question ID via splitting."""
    parts = question_id.split('-')
    if len(parts) >= 2:
        unit_part = parts[0]  # 'U1'
        lesson_part = parts[1]  # 'L2'
        
        # Strip U/L prefixes, fallback to '1' if not found
        unit_num = unit_part[1:] if unit_part.startswith('U') else '1'
        lesson_num = lesson_part[1:] if lesson_part.startswith('L') else '1'
        
        return (unit_num, lesson_num)
    return ("1", "1")

def create_lesson_key(question_id: str) -> str:
    """Create lesson grouping key like 'u1-l2' from question ID."""
    parts = question_id.split('-')[:2]  # ['U1', 'L2']
    return '-'.join(parts).lower()  # 'u1-l2'

def create_lesson_filename(unit_num: str, lesson_num: str) -> str:
    """Generate lesson filename like 'unit-1-lesson-2.edn'."""
    return f"unit-{unit_num}-lesson-{lesson_num}.edn"

def kebab_case(key: str) -> str:
    """Convert camelCase/PascalCase to kebab-case."""
    import re
    key = re.sub(r'([a-z])([A-Z])', r'\1-\2', key)
    return key.lower().replace('_', '-')

def json_to_edn_value(obj: Any) -> str:
    """Convert JSON value to EDN string representation."""
    if obj is None:
        return "nil"
    elif isinstance(obj, bool):
        return "true" if obj else "false"
    elif isinstance(obj, (int, float)):
        return str(obj)
    elif isinstance(obj, str):
        escaped = obj.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r')
        return f'"{escaped}"'
    elif isinstance(obj, list):
        items = [json_to_edn_value(item) for item in obj]
        return f"[{' '.join(items)}]"
    elif isinstance(obj, dict):
        pairs = []
        for key, value in obj.items():
            edn_key = f":{kebab_case(key)}"
            edn_value = json_to_edn_value(value)
            pairs.append(f"{edn_key} {edn_value}")
        return f"{{{' '.join(pairs)}}}"
    else:
        return json_to_edn_value(str(obj))

def convert_question_to_edn(question: Dict[str, Any]) -> Dict[str, Any]:
    """Convert a single question from JSON to EDN-compatible dict."""
    converted = {}
    for key, value in question.items():
        edn_key = kebab_case(key)
        converted[edn_key] = value
    return converted

def group_questions_by_lesson(questions: List[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
    """Group questions by lesson key using split-based approach."""
    lessons = defaultdict(list)
    
    for question in questions:
        if not isinstance(question, dict) or 'id' not in question:
            continue
            
        lesson_key = create_lesson_key(question['id'])
        lessons[lesson_key].append(question)
    
    # Sort questions within each lesson by ID
    for lesson_key in lessons:
        lessons[lesson_key].sort(key=lambda q: q['id'])
    
    return dict(lessons)

def create_index_structure(lessons_by_key: Dict[str, List[Dict[str, Any]]]) -> Dict[str, Any]:
    """Create index structure grouped by units."""
    units = defaultdict(set)
    
    # Group lessons by unit using first question ID from each lesson
    for lesson_key, questions in lessons_by_key.items():
        if questions:
            unit_num, lesson_num = extract_unit_lesson_nums(questions[0]['id'])
            units[unit_num].add(lesson_num)
    
    # Create sorted index structure
    index_units = []
    for unit_num in sorted(units.keys(), key=int):
        unit_id = f"unit-{unit_num}"
        lesson_ids = [f"lesson-{lesson_num}" for lesson_num in sorted(units[unit_num], key=int)]
        
        index_units.append({
            'id': unit_id,
            'lessons': lesson_ids
        })
    
    return {'units': index_units}

def write_edn_file(filepath: str, data: Any) -> None:
    """Write data to EDN file with compact formatting."""
    edn_content = json_to_edn_value(data)
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(edn_content)

def convert_curriculum(input_file: str, output_dir: str) -> None:
    """Main conversion function."""
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        raise ValueError(f"Failed to load curriculum JSON: {e}")
    
    if not isinstance(data, list):
        raise ValueError("Expected JSON to be a list of question objects")
    
    print(f"Loaded {len(data)} questions from {input_file}")
    
    os.makedirs(output_dir, exist_ok=True)
    
    # Group questions by lesson using split-based approach
    lessons_by_key = group_questions_by_lesson(data)
    print(f"Grouped into {len(lessons_by_key)} lessons")
    
    # Write individual lesson files
    lesson_files_written = 0
    for lesson_key, questions in lessons_by_key.items():
        # Extract unit and lesson numbers from first question
        if questions:
            unit_num, lesson_num = extract_unit_lesson_nums(questions[0]['id'])
            
            lesson_filename = create_lesson_filename(unit_num, lesson_num)
            lesson_filepath = os.path.join(output_dir, lesson_filename)
            
            # Convert questions to EDN format
            converted_questions = [convert_question_to_edn(q) for q in questions]
            
            lesson_data = {'questions': converted_questions}
            write_edn_file(lesson_filepath, lesson_data)
            lesson_files_written += 1
    
    # Create and write index file
    index_data = create_index_structure(lessons_by_key)
    index_filepath = os.path.join(output_dir, 'index.edn')
    write_edn_file(index_filepath, index_data)
    
    units_count = len(index_data['units'])
    total_questions = sum(len(questions) for questions in lessons_by_key.values())
    
    print(f"Conversion complete:")
    print(f"  - Total questions processed: {total_questions}")
    print(f"  - Units created: {units_count}")
    print(f"  - Lesson files created: {lesson_files_written}")
    print(f"  - Index file: {index_filepath}")
    print(f"  - Output directory: {output_dir}")

def main():
    """Main entry point with argument parsing."""
    parser = argparse.ArgumentParser(
        description="Convert curriculum JSON to modular EDN format",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python curriculum_to_edn_flexible.py --input curriculum.json --output resources/edn/
  python curriculum_to_edn_flexible.py -i curriculum.json -o edn_output/
        """
    )
    
    parser.add_argument(
        '--input', '-i',
        required=True,
        help='Input curriculum JSON file path'
    )
    
    parser.add_argument(
        '--output', '-o',
        required=True,
        help='Output directory for EDN files'
    )
    
    args = parser.parse_args()
    
    try:
        convert_curriculum(args.input, args.output)
    except Exception as e:
        print(f"Error: {e}")
        return 1
    
    return 0

if __name__ == '__main__':
    exit(main())