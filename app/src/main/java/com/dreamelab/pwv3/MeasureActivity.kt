package com.dreamelab.pwv3

import android.os.Bundle
import android.widget.Button
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File




    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)



        }
        }
    }

    }

    }




    }

                if (len > 0) {
                }
            }
    }
    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SerialDataBus.setListener { tSec, ch1, ch2 ->
            runOnUiThread {
                onDataReceived(tSec, ch1, ch2)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        SerialDataBus.setListener(null)
    }
}
