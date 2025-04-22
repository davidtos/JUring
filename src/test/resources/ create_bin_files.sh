#!/bin/bash

# Directory to save the files
OUTPUT_DIR="/mnt/testData/"

# Create output directory if it doesn't exist
mkdir -p "$OUTPUT_DIR"

# Target size: 10MB = 10,485,760 bytes
TARGET_SIZE=10485760

for i in $(seq 1 1000); do
  filename="$OUTPUT_DIR/$i.bin"
  echo "Creating $filename..."

  # Create a temporary file with the number
  num_str="$i"

  # Calculate how many repetitions we need to reach 10MB
  # Each digit takes 1 byte, plus we need to account for newlines
  single_num_size=${#num_str}
  repetitions=$(( TARGET_SIZE / single_num_size + 1 ))

  # Create the file by repeating the number with spaces between them
  # We create a string with the number followed by a space
  num_with_space="$num_str "

  # Use printf to repeat the number+space pattern and pipe to head to limit size
  printf "%0.s$num_with_space" $(seq 1 $((TARGET_SIZE / ${#num_with_space} + 1))) | head -c "$TARGET_SIZE" > "$filename"

  # Report the actual file size
  actual_size=$(stat -c %s "$filename")
  echo "Created $filename (size: $actual_size bytes)"
done

echo "Completed creating 1000 files."