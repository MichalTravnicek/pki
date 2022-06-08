# Authors:
#     Christian Heimes <cheimes@redhat.com>
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation; version 2 of the License.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along
# with this program; if not, write to the Free Software Foundation, Inc.,
# 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Copyright (C) 2018 Red Hat, Inc.
# All rights reserved.

from __future__ import absolute_import
import os
import pki.server.upgrade


class RemoveNSSDefault(pki.server.upgrade.PKIServerUpgradeScriptlet):

    def __init__(self):
        super(RemoveNSSDefault, self).__init__()
        self.message = 'Remove NSS_DEFAULT_DB_TYPE from instance sysconfig'

    def upgrade_instance(self, instance):
        systemd_conf = os.path.join('/etc/sysconfig', instance.name)
        self.backup(systemd_conf)

        with open(systemd_conf, 'r', encoding='utf-8') as f:
            lines = list(f)

        outlines = []
        for line in lines:
            if line.startswith('# Default NSS DB type'):
                # replace comment
                outlines.append(
                    '# Default NSS DB type is loaded from '
                    '/usr/share/pki/etc/tomcat.conf\n'
                )
            elif line.startswith('NSS_DEFAULT_DB_TYPE'):
                # skip line
                pass
            else:
                # add the rest
                outlines.append(line)

        with open(systemd_conf, 'w', encoding='utf-8') as f:
            f.writelines(outlines)
