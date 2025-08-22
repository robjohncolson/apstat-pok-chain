#!/usr/bin/env python3
"""
Curriculum JSON to EDN Converter
Converts large curriculum.json files to modular EDN format for ClojureScript apps.
Optimized for lazy-loading in offline, low-power school environments.
"""

import json
import argparse
import os
import re
from typing import Dict, List, Any, Union

def slugify(text: str) -> str:
    """Convert text to URL-safe slug format."""
    text = str(text).lower().strip()
    text = re.sub(r'[^\w\s-]', '', text)
    text = re.sub(r'[-\s]+', '-', text)
    return text.strip('-')

def json_to_edn_value(obj: Any) -> str:
    """Convert JSON value to EDN string representation."""
    if obj is None:
        return "nil"
    elif isinstance(obj, bool):
        return "true" if obj else "false"
    elif isinstance(obj, (int, float)):
        return str(obj)
    elif isinstance(obj, str):
        # Escape quotes and newlines for EDN strings
        escaped = obj.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r')
        return f'"{escaped}"'
    elif isinstance(obj, list):
        items = [json_to_edn_value(item) for item in obj]
        return f"[{' '.join(items)}]"
    elif isinstance(obj, dict):
        pairs = []
        for key, value in obj.items():
            edn_key = f":{slugify(key)}" if isinstance(key, str) else json_to_edn_value(key)
            edn_value = json_to_edn_value(value)
            pairs.append(f"{edn_key} {edn_value}")
        return f"{{{' '.join(pairs)}}}"
    else:
        # Fallback for unknown types
        return json_to_edn_value(str(obj))

def extract_lessons_from_unit(unit: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Extract lesson data from a unit, handling various JSON structures."""
    lessons = []
    
    # Handle different possible structures
    if 'lessons' in unit:
        lessons = unit['lessons']
    elif 'chapters' in unit:
        lessons = unit['chapters']
    elif 'topics' in unit:
        lessons = unit['topics']
    else:
        # If unit itself contains lesson-like data
        if 'questions' in unit or 'content' in unit:
            lessons = [unit]
    
    return lessons if isinstance(lessons, list) else []

def create_lesson_filename(unit_id: str, lesson_id: str) -> str:
    """Generate standardized lesson filename."""
    unit_slug = slugify(unit_id)
    lesson_slug = slugify(lesson_id)
    return f"{unit_slug}-{lesson_slug}.edn"

def process_lesson_data(lesson: Dict[str, Any]) -> Dict[str, Any]:
    """Process and optimize lesson data for EDN output."""
    processed = {}
    
    # Core lesson metadata
    if 'id' in lesson:
        processed['id'] = lesson['id']
    if 'name' in lesson or 'title' in lesson:
        processed['name'] = lesson.get('name') or lesson.get('title')
    if 'description' in lesson:
        processed['description'] = lesson['description']
    
    # Questions/content
    if 'questions' in lesson:
        processed['questions'] = lesson['questions']
    elif 'items' in lesson:
        processed['questions'] = lesson['items']
    elif 'content' in lesson:
        processed['content'] = lesson['content']
    
    # Chart/visualization data
    if 'chart_data' in lesson:
        processed['chart-data'] = lesson['chart_data']
    if 'vega_spec' in lesson:
        processed['vega-spec'] = lesson['vega_spec']
    if 'visualizations' in lesson:
        processed['visualizations'] = lesson['visualizations']
    
    # Learning objectives
    if 'objectives' in lesson:
        processed['objectives'] = lesson['objectives']
    if 'standards' in lesson:
        processed['standards'] = lesson['standards']
    
    return processed

def write_edn_file(filepath: str, data: Any) -> None:
    """Write data to EDN file with compact formatting."""
    edn_content = json_to_edn_value(data)
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(edn_content)

def parse_question_id(question_id: str) -> tuple:
    """Parse question ID like 'U1-L2-Q01' into (unit, lesson, question)."""
    import re
    match = re.match(r'U(\d+)-L(\d+)-Q(\d+)', question_id)
    if match:
        return (f"unit-{match.group(1)}", f"lesson-{match.group(2)}", f"question-{match.group(3)}")
    return ("unknown-unit", "unknown-lesson", "unknown-question")

def group_questions_by_lesson(questions: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    """Group flat question list by unit and lesson."""
    units = {}
    
    for question in questions:
        if not isinstance(question, dict) or 'id' not in question:
            continue
            
        unit_id, lesson_id, question_num = parse_question_id(question['id'])
        
        # Initialize unit if not exists
        if unit_id not in units:
            units[unit_id] = {
                'id': unit_id,
                'name': f"Unit {unit_id.split('-')[1]}",
                'lessons': {}
            }
        
        # Initialize lesson if not exists
        if lesson_id not in units[unit_id]['lessons']:
            units[unit_id]['lessons'][lesson_id] = {
                'id': lesson_id,
                'name': f"Lesson {lesson_id.split('-')[1]}",
                'questions': []
            }
        
        # Add question to lesson
        units[unit_id]['lessons'][lesson_id]['questions'].append(question)
    
    return units

def convert_curriculum(input_file: str, output_dir: str) -> None:
    """Main conversion function."""
    # Load JSON data
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        raise ValueError(f"Failed to load curriculum JSON: {e}")
    
    # Create output directory
    os.makedirs(output_dir, exist_ok=True)
    
    # Handle flat question array structure
    if isinstance(data, list):
        # Flat array of questions - group by unit/lesson
        units_data = group_questions_by_lesson(data)
    elif isinstance(data, dict):
        if 'units' in data:
            units_data = data['units']
        elif 'curriculum' in data:
            units_data = data['curriculum']
        elif 'questions' in data:
            # Questions array inside object
            units_data = group_questions_by_lesson(data['questions'])
        else:
            # Treat entire dict as single unit
            units_data = {'unit-1': {'id': 'unit-1', 'name': 'Unit 1', 'lessons': {'lesson-1': {'id': 'lesson-1', 'name': 'Lesson 1', 'questions': [data]}}}}
    else:
        raise ValueError("Invalid curriculum data structure")
    
    if not units_data:
        raise ValueError("No units found in curriculum data")
    
    # Build index structure and write lesson files
    index_units = []
    lesson_files_written = 0
    
    for unit_id, unit_data in units_data.items():
        unit_name = unit_data.get('name', unit_id)
        lesson_ids = []
        
        for lesson_id, lesson_data in unit_data.get('lessons', {}).items():
            lesson_name = lesson_data.get('name', lesson_id)
            
            # Create lesson file
            lesson_filename = create_lesson_filename(unit_id, lesson_id)
            lesson_filepath = os.path.join(output_dir, lesson_filename)
            
            # Process lesson data for EDN
            processed_lesson = {
                'id': lesson_id,
                'name': lesson_name,
                'questions': lesson_data.get('questions', [])
            }
            
            write_edn_file(lesson_filepath, processed_lesson)
            lesson_ids.append(lesson_id)
            lesson_files_written += 1
        
        if lesson_ids:  # Only include units with lessons
            index_units.append({
                'id': unit_id,
                'name': unit_name,
                'lessons': lesson_ids
            })
    
    # Write index file
    index_data = {'units': index_units}
    index_filepath = os.path.join(output_dir, 'index.edn')
    write_edn_file(index_filepath, index_data)
    
    print(f"Conversion complete:")
    print(f"  - Units processed: {len(index_units)}")
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
  python curriculum_to_edn_flexible.py -i data/curriculum.json -o output/
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
