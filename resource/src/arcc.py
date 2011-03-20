#! /usr/bin/env python

import stat, os, sys, struct
from optparse import OptionParser

class Entry(object):
    def __init__(self, root, filename):
        rel_name = os.path.relpath(filename, root)
        rel_name = rel_name.replace('\\', '/')

        size = os.stat(filename)[stat.ST_SIZE]
        f = open(filename, 'rb')
        self.filename = rel_name
        self.resource = f.read()
        self.size = size
        f.close()

    def __repr__(self):
        return '%s(%d)' % (self.filename, self.size)

def align_file(file, align):
    new_pos = file.tell() + (align - 1)
    new_pos &= ~(align-1)
    file.seek(new_pos)

def compile(input_files, options):
    # Sort file-names. Names must be sorted for binary search at run-time.
    input_files.sort()

    out_file = open(options.output_file, 'wb')
    # Version
    out_file.write(struct.pack('!I', 1))
    # EntryCount (dummy)
    out_file.write(struct.pack('!I', 0))
    # EntryOffset (dummy)
    out_file.write(struct.pack('!I', 0))

    entries = []
    strings_offset = []
    for i,f in enumerate(input_files):
        e = Entry(options.root, f)
        # Store offset to string
        strings_offset.append(out_file.tell())
        # Write filename string
        out_file.write(e.filename)
        out_file.write(chr(0))
        entries.append(e)

    resources_offset = []
    for i,e in enumerate(entries):
        align_file(out_file, 4)
        resources_offset.append(out_file.tell())
        out_file.write(e.resource)

    align_file(out_file, 4)
    entry_offset = out_file.tell()
    for i,e in enumerate(entries):
        out_file.write(struct.pack('!I', strings_offset[i]))
        out_file.write(struct.pack('!I', resources_offset[i]))
        out_file.write(struct.pack('!I', e.size))

    # Reset file and write actual offsets
    out_file.seek(0)
    # Version
    out_file.write(struct.pack('!I', 1))
    # EntryCount
    out_file.write(struct.pack('!I', len(entries)))
    # EntryOffset
    out_file.write(struct.pack('!I', entry_offset))

    out_file.close()

if __name__ == '__main__':
    usage = 'usage: %prog [options] files'
    parser = OptionParser(usage)
    parser.add_option('-r', dest='root', help='Root directory', metavar='ROOT', default='')
    parser.add_option('-o', dest='output_file', help='Output file', metavar='OUTPUT')
    (options, args) = parser.parse_args()
    if not options.output_file:
        parser.error('Output file not specified (-o)')

    try:
        compile(args, options)
    except:
        # Try to remove the outfile in case of any errors
        if os.path.exists(options.output_file):
            try:
                os.remove(options.output_file)
            except:
                pass
        raise
